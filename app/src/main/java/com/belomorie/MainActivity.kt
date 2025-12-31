package com.belomorie

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.belomorie.service.BelomorieService

class MainActivity : AppCompatActivity() {
    
    private lateinit var serviceButton: Button
    private lateinit var statusText: TextView
    private lateinit var statusIndicator: View
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        serviceButton = findViewById(R.id.serviceButton)
        statusText = findViewById(R.id.statusText)
        statusIndicator = findViewById(R.id.statusIndicator)
        
        serviceButton.setOnClickListener { 
            if (isServiceRunning()) {
                stopService()
            } else {
                startService()
            }
        }
        
        // Обновляем статус при создании активности
        updateServiceStatus()
    }
    
    override fun onResume() {
        super.onResume()
        // Обновляем статус при возврате на экран
        updateServiceStatus()
    }
    
    private fun startService() {
        if (checkPermissions()) {
            val intent = Intent(this, BelomorieService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            // Обновляем UI после небольшой задержки
            serviceButton.postDelayed({ updateServiceStatus() }, 500)
        } else {
            requestPermissions()
        }
    }
    
    private fun stopService() {
        val intent = Intent(this, BelomorieService::class.java)
        stopService(intent)
        updateServiceStatus()
    }
    
    private fun isServiceRunning(): Boolean {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val services = activityManager.getRunningServices(Integer.MAX_VALUE)
        
        for (service in services) {
            if (BelomorieService::class.java.name == service.service.className) {
                return true
            }
        }
        return false
    }
    
    private fun updateServiceStatus() {
        val isRunning = isServiceRunning()
        
        if (isRunning) {
            statusText.text = "Статус: Запущен"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark))
            statusIndicator.setBackgroundResource(android.R.drawable.presence_online)
            serviceButton.text = "⏹ ОСТАНОВИТЬ СЕРВИС"
        } else {
            statusText.text = "Статус: Остановлен"
            statusText.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray))
            statusIndicator.setBackgroundResource(android.R.drawable.presence_offline)
            serviceButton.text = "🚀 ЗАПУСТИТЬ СЕРВИС"
        }
    }
    
    private fun checkPermissions(): Boolean {
        val permission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        return permission == PackageManager.PERMISSION_GRANTED
    }
    
    private fun requestPermissions() {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 1001)
    }
    
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 1001 && grantResults.isNotEmpty() && 
            grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startService()
        }
    }
}

