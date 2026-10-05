package dev.nordfjell.auth;

enum AuthState {
    LOADING,
    LOGIN_REQUIRED,
    REGISTER_REQUIRED,
    AUTHENTICATING,
    AUTHENTICATED
}
