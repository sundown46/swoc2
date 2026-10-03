package io.swoc2.app.picture;

import io.swoc2.domain.geo.Mgrs;
import io.swoc2.domain.picture.Contact;
import io.swoc2.domain.picture.ContactKind;
import io.swoc2.domain.picture.ContactOverride;
import io.swoc2.domain.picture.ContactState;
import io.swoc2.domain.picture.ContactUpdate;
import io.swoc2.domain.picture.Dimension;
import io.swoc2.domain.picture.Identity;
import io.swoc2.domain.picture.SourceKey;
import io.swoc2.domain.picture.SymbolCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * The live picture (PIC-001): one shared, server-authoritative contact store for all users, in
 * memory (ARCHITECTURE §10), indexed by source key, internal id and MGRS 100 km cell (ADR 0013).
 * Thread-safe: updates to one contact are atomic; listeners are notified after each change.
 *
 * <p>Source values and operator overrides are kept apart (§5.2) and only combined on read, so a
 * source update never loses an operator's edit and resetting an override restores the source value.
 */
@Component
public class PictureStore {

    private static final Logger log = LoggerFactory.getLogger(PictureStore.class);

    /** Source values (no overrides applied). */
    private final Map<SourceKey, Contact> bySource = new ConcurrentHashMap<>();

    private final Map<UUID, SourceKey> byId = new ConcurrentHashMap<>();
    private final Map<SourceKey, String> cellOf = new ConcurrentHashMap<>();
    private final Map<String, Set<SourceKey>> byCell = new ConcurrentHashMap<>();
    private final Map<SourceKey, ContactOverride> overrides = new ConcurrentHashMap<>();
    private final List<PictureListener> listeners = new CopyOnWriteArrayList<>();

    public void addListener(PictureListener listener) {
        listeners.add(listener);
    }

    /** Applies one report (ARCHITECTURE §4 step 5). Returns the resolved contact. */
    public Contact upsert(ContactUpdate update, Instant receivedAt) {
        Contact[] previous = new Contact[1];
        Contact merged = bySource.compute(update.key(), (key, old) -> {
            previous[0] = old;
            return merge(old, update, receivedAt);
        });
        byId.put(merged.id(), merged.key());
        String cell = Mgrs.cell100km(merged.position());
        String oldCell = cellOf.put(merged.key(), cell);
        if (!cell.equals(oldCell)) {
            byCell.computeIfAbsent(cell, c -> ConcurrentHashMap.newKeySet()).add(merged.key());
            if (oldCell != null) {
                removeFromCell(oldCell, merged.key());
            }
        }
        Contact resolved = resolve(merged);
        notify(new PictureChange(
                PictureChange.Type.UPSERT, resolved, cell, oldCell != null && !oldCell.equals(cell) ? oldCell : null));
        return resolved;
    }

    private static Contact merge(Contact old, ContactUpdate u, Instant receivedAt) {
        String sidc = u.sidc() != null
                ? u.sidc()
                : old != null && old.symbol() != null ? old.symbol().code() : null;
        Map<String, String> ids = new HashMap<>(old == null ? Map.of() : old.ids());
        ids.putAll(u.ids());
        return new Contact(
                old == null ? UUID.randomUUID() : old.id(),
                u.key(),
                u.sourceType() != null ? u.sourceType() : old == null ? null : old.sourceType(),
                u.sourceIndicator() != null ? u.sourceIndicator() : old == null ? null : old.sourceIndicator(),
                u.kind(),
                sidc == null ? null : SymbolCode.sidc(sidc),
                sidc == null ? Identity.UNKNOWN : Identity.fromSidc(sidc),
                sidc == null ? Dimension.UNKNOWN : Dimension.fromSidc(sidc),
                u.name() != null ? u.name() : old == null ? null : old.name(),
                old == null ? null : old.remarks(),
                u.position(),
                u.course(),
                u.heading(),
                u.speed(),
                u.sourceTime(),
                receivedAt,
                ContactState.LIVE,
                u.classification() != null ? u.classification() : old == null ? null : old.classification(),
                ids,
                u.raw(),
                null);
    }

    /** Applies the operator override layer (§5.2): override wins for descriptive fields. */
    private Contact resolve(Contact source) {
        ContactOverride o = overrides.getOrDefault(source.key(), ContactOverride.NONE);
        if (o.isEmpty()) {
            return source;
        }
        SymbolCode symbol = o.sidc() != null ? SymbolCode.sidc(o.sidc()) : source.symbol();
        String code = symbol == null ? null : symbol.code();
        Identity identity =
                o.identity() != null ? o.identity() : o.sidc() != null ? Identity.fromSidc(code) : source.identity();
        Dimension dimension = o.sidc() != null ? Dimension.fromSidc(code) : source.dimension();
        return new Contact(
                source.id(),
                source.key(),
                source.sourceType(),
                source.sourceIndicator(),
                source.kind(),
                symbol,
                identity,
                dimension,
                o.name() != null ? o.name() : source.name(),
                o.remarks() != null ? o.remarks() : source.remarks(),
                source.position(),
                source.course(),
                source.heading(),
                source.speed(),
                source.sourceTime(),
                source.receivedAt(),
                source.state(),
                source.classification(),
                source.ids(),
                source.raw(),
                o);
    }

    public Optional<Contact> get(UUID id) {
        SourceKey key = byId.get(id);
        Contact c = key == null ? null : bySource.get(key);
        return Optional.ofNullable(c).map(this::resolve);
    }

    public Optional<Contact> get(SourceKey key) {
        return Optional.ofNullable(bySource.get(key)).map(this::resolve);
    }

    /** All contacts, resolved. A copy: safe to iterate while the picture changes. */
    public List<Contact> all() {
        return bySource.values().stream().map(this::resolve).toList();
    }

    /** Contacts in the given MGRS 100 km cells (viewport queries, M4). */
    public List<Contact> inCells(Collection<String> cells) {
        List<Contact> result = new ArrayList<>();
        for (String cell : cells) {
            for (SourceKey key : byCell.getOrDefault(cell, Set.of())) {
                Contact c = bySource.get(key);
                if (c != null) {
                    result.add(resolve(c));
                }
            }
        }
        return result;
    }

    public int size() {
        return bySource.size();
    }

    /** Source-value snapshot for aging (no override resolution needed there). */
    Collection<Contact> sourceValues() {
        return List.copyOf(bySource.values());
    }

    /** Marks a contact stale if it is still live and was not updated since {@code receivedAt}. */
    void markStale(SourceKey key, Instant receivedAt) {
        Contact[] changed = new Contact[1];
        bySource.computeIfPresent(key, (k, c) -> {
            if (c.state() == ContactState.STALE || !c.receivedAt().equals(receivedAt)) {
                return c;
            }
            Contact stale = new Contact(
                    c.id(),
                    c.key(),
                    c.sourceType(),
                    c.sourceIndicator(),
                    c.kind(),
                    c.symbol(),
                    c.identity(),
                    c.dimension(),
                    c.name(),
                    c.remarks(),
                    c.position(),
                    c.course(),
                    c.heading(),
                    c.speed(),
                    c.sourceTime(),
                    c.receivedAt(),
                    ContactState.STALE,
                    c.classification(),
                    c.ids(),
                    c.raw(),
                    null);
            changed[0] = stale;
            return stale;
        });
        if (changed[0] != null) {
            notify(new PictureChange(PictureChange.Type.UPSERT, resolve(changed[0]), cellOf.get(key), null));
        }
    }

    /** Removes a contact if it was not updated since {@code receivedAt} (aging) - or always if null. */
    boolean remove(SourceKey key, Instant receivedAt) {
        Contact[] removed = new Contact[1];
        bySource.computeIfPresent(key, (k, c) -> {
            if (receivedAt != null && !c.receivedAt().equals(receivedAt)) {
                return c;
            }
            removed[0] = c;
            return null;
        });
        if (removed[0] == null) {
            return false;
        }
        byId.remove(removed[0].id());
        String cell = cellOf.remove(key);
        if (cell != null) {
            removeFromCell(cell, key);
        }
        notify(new PictureChange(PictureChange.Type.REMOVE, resolve(removed[0]), cell, null));
        return true;
    }

    private void removeFromCell(String cell, SourceKey key) {
        byCell.computeIfPresent(cell, (c, keys) -> {
            keys.remove(key);
            return keys.isEmpty() ? null : keys;
        });
    }

    /** Installs or clears (NONE) an override and re-publishes the contact if present. */
    void setOverride(SourceKey key, ContactOverride override) {
        if (override == null || override.isEmpty()) {
            overrides.remove(key);
        } else {
            overrides.put(key, override);
        }
        Contact c = bySource.get(key);
        if (c != null) {
            notify(new PictureChange(PictureChange.Type.UPSERT, resolve(c), cellOf.get(key), null));
        }
    }

    /** Loads persisted overrides at startup without notifying (nothing is subscribed yet). */
    void loadOverrides(Map<SourceKey, ContactOverride> persisted) {
        overrides.putAll(persisted);
    }

    void clearOverrides() {
        overrides.clear();
    }

    /**
     * Wipes the live picture (PIC-005): every contact except user-created ones. Returns the number
     * of removed contacts.
     */
    int wipe() {
        int removed = 0;
        for (Contact c : sourceValues()) {
            if (c.kind() != ContactKind.USER_CONTACT && remove(c.key(), null)) {
                removed++;
            }
        }
        return removed;
    }

    /** Highest classification of the displayed data (GEN-011), or null if none is known. */
    public Character highestClassification() {
        String order = "PURCST";
        Character best = null;
        for (Contact c : bySource.values()) {
            Character cl = c.classification();
            if (cl != null && order.indexOf(cl) >= 0 && (best == null || order.indexOf(cl) > order.indexOf(best))) {
                best = cl;
            }
        }
        return best;
    }

    private void notify(PictureChange change) {
        for (PictureListener listener : listeners) {
            try {
                listener.onChange(change);
            } catch (RuntimeException e) {
                log.error("Picture listener {} failed", listener.getClass().getName(), e);
            }
        }
    }
}
