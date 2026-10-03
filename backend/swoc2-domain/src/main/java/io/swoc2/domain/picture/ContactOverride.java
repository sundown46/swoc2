package io.swoc2.domain.picture;

/**
 * Operator edits to a contact's descriptive fields (PIC-003, ARCHITECTURE §5.2). A non-null field
 * wins over the source value until it is reset; kinematics are never overridable. Stored
 * separately from the contact, so source updates never lose the edit.
 */
public record ContactOverride(String name, String remarks, Identity identity, String sidc) {

    public static final ContactOverride NONE = new ContactOverride(null, null, null, null);

    public ContactOverride {
        if (name != null && (name.isBlank() || name.length() > 64)) {
            throw new IllegalArgumentException("name must be 1-64 characters");
        }
        if (remarks != null && remarks.length() > 2000) {
            throw new IllegalArgumentException("remarks longer than 2000 characters");
        }
        if (sidc != null && !sidc.matches("[A-Za-z*\\-]{15}")) {
            throw new IllegalArgumentException("sidc must be a 15-character letter SIDC");
        }
    }

    public boolean isEmpty() {
        return name == null && remarks == null && identity == null && sidc == null;
    }
}
