package de.finnwessel.mediabridge

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log

/** Announces the API via mDNS as _mediabridge._tcp so Home Assistant discovers it. */
class Discovery(context: Context) {
    private val nsd = context.getSystemService(NsdManager::class.java)
    private var listener: NsdManager.RegistrationListener? = null

    fun register(name: String, port: Int, id: String, version: String) {
        if (listener != null) return
        val info = NsdServiceInfo().apply {
            serviceName = name
            serviceType = SERVICE_TYPE
            setPort(port)
            setAttribute("id", id)
            setAttribute("version", version)
            setAttribute("api", API_VERSION.toString())
        }
        val l = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(info: NsdServiceInfo) = Log.i(TAG, "mDNS registered as ${info.serviceName}")
            override fun onRegistrationFailed(info: NsdServiceInfo, code: Int) = Log.w(TAG, "mDNS registration failed: $code")
            override fun onServiceUnregistered(info: NsdServiceInfo) = Log.i(TAG, "mDNS unregistered")
            override fun onUnregistrationFailed(info: NsdServiceInfo, code: Int) = Unit
        }
        listener = l
        runCatching { nsd.registerService(info, NsdManager.PROTOCOL_DNS_SD, l) }
            .onFailure { Log.w(TAG, "mDNS failed: ${it.message}"); listener = null }
    }

    fun unregister() {
        listener?.let { runCatching { nsd.unregisterService(it) } }
        listener = null
    }

    companion object {
        const val SERVICE_TYPE = "_mediabridge._tcp"
        const val API_VERSION = 1
    }
}
