package io.serena.javarefactor.shared;

/** Conservative side-effect classification for Java expressions. */
public enum ExpressionPurity {
    PURE,
    ALLOCATION_ONLY,
    SIDE_EFFECTING,
    UNKNOWN
}
