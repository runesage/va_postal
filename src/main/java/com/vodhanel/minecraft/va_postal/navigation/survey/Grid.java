package com.vodhanel.minecraft.va_postal.navigation.survey;

/** The blocks a survey runs over, as {@link Cell}s. Must be safe to read off the main thread. */
public interface Grid {
    Cell cell(int x, int y, int z);
}
