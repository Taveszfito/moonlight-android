package com.limelight.ui;

import com.limelight.binding.input.GameInputDevice;

public interface GameGestures {
    void toggleKeyboard();

    default void showGameMenu(GameInputDevice device){};

    default boolean handleControllerMenuInput(int buttonFlags, float leftStickX,
                                              float leftStickY) {
        return false;
    }

    default boolean isControllerMenuOpen() {
        return false;
    }
}
