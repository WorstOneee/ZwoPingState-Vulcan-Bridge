package dev.zwo.pingstate.core;

/** Mesmo significado de {@code ConnectionState} da ponte; mantido aqui para o núcleo não depender dela. */
public enum PingState {
    STABLE,
    UNSTABLE,
    UNKNOWN
}
