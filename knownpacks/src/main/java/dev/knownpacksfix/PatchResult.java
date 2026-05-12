package dev.knownpacksfix;

public class PatchResult {

    private final String fieldName;
    private final int oldValue;
    private final int newValue;
    private final boolean verified;
    private final boolean wasFinalField;

    PatchResult(String fieldName, int oldValue, int newValue, boolean verified, boolean wasFinalField) {
        this.fieldName = fieldName;
        this.oldValue = oldValue;
        this.newValue = newValue;
        this.verified = verified;
        this.wasFinalField = wasFinalField;
    }

    public String getFieldName() {
        return fieldName;
    }

    public int getOldValue() {
        return oldValue;
    }

    public int getNewValue() {
        return newValue;
    }

    public boolean isVerified() {
        return verified;
    }

    public boolean wasFinalField() {
        return wasFinalField;
    }
}
