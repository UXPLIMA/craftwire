package com.uxplima.craftwire.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

public final class Json {
    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private Json() {}
}
