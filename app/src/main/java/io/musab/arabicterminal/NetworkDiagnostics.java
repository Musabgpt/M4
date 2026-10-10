package io.musab.arabicterminal;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import java.net.InetAddress;
import java.net.URL;
import javax.net.ssl.HttpsURLConnection;

/** Android-side diagnostics: distinguishes networking failures from Alpine apk indexes. */
public final class NetworkDiagnostics {
    private NetworkDiagnostics(){}
    public static String check(Context context){
        StringBuilder result=new StringBuilder("فحص شبكة الطرفية:\n");
        try{
            ConnectivityManager manager=(ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
            Network network=manager==null?null:manager.getActiveNetwork();
            result.append("شبكة الهاتف: ").append(network==null?
                "لا توجد شبكة نشطة":"موجودة").append('\n');
            LinkProperties links=network==null?null:manager.getLinkProperties(network);
            result.append("خوادم DNS: ");
            if(links!=null&&!links.getDnsServers().isEmpty()){
                for(InetAddress address:links.getDnsServers())
                    result.append(address.getHostAddress()).append(' ');
            }else result.append("غير متاحة");
            result.append('\n');
            InetAddress address=InetAddress.getByName("dl-cdn.alpinelinux.org");
            result.append("DNS للمستودع: ").append(address.getHostAddress()).append('\n');
            HttpsURLConnection connection=(HttpsURLConnection)new URL(
                "https://dl-cdn.alpinelinux.org/alpine/v3.24/main/aarch64/APKINDEX.tar.gz")
                .openConnection();
            connection.setRequestMethod("HEAD");
            connection.setConnectTimeout(6000);connection.setReadTimeout(6000);
            int status=connection.getResponseCode();
            result.append("اتصال HTTPS من أندرويد: HTTP ").append(status).append('\n');
            connection.disconnect();
            if(status>=200&&status<400)result.append(
                "الشبكة من أندرويد تعمل. إذا استمر فشل apk، فراجع إعداد DNS أو SSL داخل PRoot.");
        }catch(Exception e){
            result.append("فشل اتصال أندرويد: ").append(e.getClass().getSimpleName());
            if(e.getMessage()!=null)result.append(" — ").append(e.getMessage());
        }
        return result.toString();
    }
}
