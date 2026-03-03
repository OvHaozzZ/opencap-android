package ai.opencap.android

import android.app.Application
import com.google.firebase.FirebaseApp

class OpenCapApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        FirebaseApp.initializeApp(this)
    }
}
