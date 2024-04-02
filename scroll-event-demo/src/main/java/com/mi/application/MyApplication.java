package com.mi.application;

import android.app.Application;

import com.mi.slide_card.config.CardConfig;

public class MyApplication extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        CardConfig.initConfig(this);
    }
}
