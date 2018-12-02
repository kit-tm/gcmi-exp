package com.github.sherter.jcon.networking;

public interface BackPressureable {
    void pressure();

    void unpressure();
}
