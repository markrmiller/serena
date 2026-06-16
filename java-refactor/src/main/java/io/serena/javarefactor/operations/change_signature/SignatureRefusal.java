package io.serena.javarefactor.operations.change_signature;

/** A structured refusal raised by the change-signature / introduce-parameter planner and its helper units. */
public final class SignatureRefusal extends Exception {
    private final String code;

    public SignatureRefusal(String code, String message) {
        super(message);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
