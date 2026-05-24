package com.example.smartcalculator

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeManager.applySaved(this)
    }
}
