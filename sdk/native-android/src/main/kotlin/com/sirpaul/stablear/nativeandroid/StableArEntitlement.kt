package com.sirpaul.stablear.nativeandroid

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.X509EncodedKeySpec
import java.util.Base64

enum class EntitlementState { INVALID, VALID, OFFLINE_GRACE }

object StableArEntitlement {
    fun verify(context:Context,token:String,publicKeyPem:String,feature:String="tracking",nowUnixS:Long=System.currentTimeMillis()/1000):EntitlementState {
        val parts=token.split('.');if(parts.size!=3||parts[0]!="STABLEAR1")return EntitlementState.INVALID
        val signingInput="${parts[0]}.${parts[1]}";val signature=try{Base64.getUrlDecoder().decode(parts[2])}catch(_:Exception){return EntitlementState.INVALID}
        val der=try{Base64.getMimeDecoder().decode(publicKeyPem.replace("-----BEGIN PUBLIC KEY-----","").replace("-----END PUBLIC KEY-----",""))}catch(_:Exception){return EntitlementState.INVALID}
        val key=try{KeyFactory.getInstance("EC").generatePublic(X509EncodedKeySpec(der))}catch(_:Exception){return EntitlementState.INVALID}
        val valid=try{Signature.getInstance("SHA256withECDSA").run{initVerify(key);update(signingInput.toByteArray(Charsets.US_ASCII));verify(signature)}}catch(_:Exception){false}
        if(!valid)return EntitlementState.INVALID
        return when(NativeStableAr.entitlementClaimsStatus(token,"stablear","android",androidAppBinding(context),feature,nowUnixS,true)){1->EntitlementState.VALID;2->EntitlementState.OFFLINE_GRACE;else->EntitlementState.INVALID}
    }

    @Suppress("DEPRECATION")
    fun androidAppBinding(context:Context):String {
        val pm=context.packageManager;val pkg=context.packageName
        val certs=if(Build.VERSION.SDK_INT>=28){
            val info=pm.getPackageInfo(pkg,PackageManager.GET_SIGNING_CERTIFICATES).signingInfo
            if(info.hasMultipleSigners())info.apkContentsSigners else info.signingCertificateHistory
        }else pm.getPackageInfo(pkg,PackageManager.GET_SIGNATURES).signatures
        val cert=certs.firstOrNull()?.toByteArray()?:error("No signing certificate for $pkg")
        val digest=MessageDigest.getInstance("SHA-256").digest(cert).joinToString(""){"%02x".format(it)}
        return "$pkg:$digest"
    }
}
