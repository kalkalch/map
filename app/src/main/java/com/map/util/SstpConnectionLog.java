package com.map.util;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.Uri;
import android.os.Build;
import androidx.core.content.FileProvider;
import com.map.BuildConfig;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Optional SSTP connection log stored in the app cache for easy sharing.
 * The log is sanitized and rewritten on every new SSTP connect attempt.
 */
public final class SstpConnectionLog {
    private static final String LOG_DIR = "logs";
    private static final String LOG_FILE = "sstp-connection.log";
    private static final Object LOCK = new Object();
    private static final Pattern KEY_VALUE_PATTERN =
        Pattern.compile("(?i)(password|passwd|pass)\\s*[=:]\\s*[^\\s,;]+");
    private static final Pattern QUOTED_SECRET_PATTERN =
        Pattern.compile("(?i)(password|passwd|pass)\\s+\"[^\"]*\"");

    private static volatile Context appContext;

    private SstpConnectionLog() {}

    public static void initialize(Context context) {
        if (context != null) {
            appContext = context.getApplicationContext();
        }
    }

    public static void startSession(LocalSettings settings) {
        Context context = appContext;
        if (context == null || settings == null) {
            return;
        }
        synchronized (LOCK) {
            if (!settings.isSstpConnectionLoggingEnabled()) {
                clear();
                return;
            }
            File file = getLogFile(context);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(file, false)) {
                writeSafeLine(out, "MAP SSTP connection log");
                writeSafeLine(out, "Started: " + timestamp());
                writeSafeLine(out, "App version: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")");
                writeSafeLine(out, "SSTP enabled: " + settings.isSstpEnabled());
                writeSafeLine(out, "Server configured: " + !settings.getSstpHost().isBlank());
                writeSafeLine(out, "SSTP target: " + settings.getSstpHost() + ":" + settings.getSstpPort());
                writeSafeLine(out, "SSTP resolved endpoint: pending");
                writeSafeLine(out, "Port: " + settings.getSstpPort());
                writeSafeLine(out, "Username configured: " + !settings.getSstpUser().isBlank());
                writeSafeLine(out, "Ignore cert errors: " + settings.isSstpIgnoreCertErrors());
                writeSafeLine(out, "Reconnect enabled: " + settings.isSstpReconnectEnabled());
                writeSafeLine(out, "Reconnect delay sec: " + settings.getSstpReconnectDelaySec());
                writeSafeLine(out, "Reconnect attempts: " + settings.getSstpReconnectAttempts());
                writeSafeLine(out, "Remote upstream configured: " + settings.hasAnyUpstreamConfigured());
                writeSafeLine(out, "SOCKS5 upstream: " + describeSocks5Upstream(settings));
                writeSafeLine(out, "HTTP upstream: " + describeHttpUpstream(settings));
                writeSafeLine(out, "Cascade probe host: " + settings.getProxyHealthcheckHost());
                writeSafeLine(out, "Cascade probe interval sec: " + settings.getProxyHealthcheckIntervalSec());
                writeSafeLine(out, "Network: " + describeActiveNetwork(context));
                writeSafeLine(out, "");
            } catch (Exception ignored) {
            }
        }
    }

    public static void onLoggingPreferenceChanged(boolean enabled) {
        if (!enabled) {
            clear();
        }
    }

    public static void log(String source, String message) {
        append("INFO", source, message, null);
    }

    public static void logError(String source, String message, Throwable t) {
        append("ERROR", source, message, t);
    }

    public static boolean hasLog() {
        Context context = appContext;
        if (context == null) {
            return false;
        }
        File file = getLogFile(context);
        return file.exists() && file.isFile() && file.length() > 0;
    }

    public static void clear() {
        Context context = appContext;
        if (context == null) {
            return;
        }
        synchronized (LOCK) {
            File file = getLogFile(context);
            if (file.exists()) {
                file.delete();
            }
        }
    }

    public static Intent buildShareIntent(Context context) {
        Context safeContext = context != null ? context.getApplicationContext() : appContext;
        if (safeContext == null) {
            return null;
        }
        File file = getLogFile(safeContext);
        if (!file.exists() || file.length() == 0L) {
            return null;
        }
        Uri uri = FileProvider.getUriForFile(
            safeContext,
            safeContext.getPackageName() + ".fileprovider",
            file
        );
        Intent intent = new Intent(Intent.ACTION_SEND);
        intent.setType("text/plain");
        intent.putExtra(Intent.EXTRA_STREAM, uri);
        intent.putExtra(Intent.EXTRA_SUBJECT, "MAP SSTP connection log");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        return intent;
    }

    private static void append(String level, String source, String message, Throwable t) {
        Context context = appContext;
        if (context == null) {
            return;
        }
        LocalSettings settings = new LocalSettings(context);
        if (!settings.isSstpConnectionLoggingEnabled()) {
            return;
        }
        synchronized (LOCK) {
            File file = getLogFile(context);
            File parent = file.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(file, true)) {
                StringBuilder line = new StringBuilder()
                    .append(timestamp())
                    .append(" [")
                    .append(level)
                    .append("] ");
                if (source != null && !source.isEmpty()) {
                    line.append(source).append(": ");
                }
                line.append(sanitize(message));
                if (t != null) {
                    line.append(" | ").append(t.getClass().getSimpleName());
                    if (t.getMessage() != null && !t.getMessage().isEmpty()) {
                        line.append(": ").append(sanitize(t.getMessage()));
                    }
                }
                writeLine(out, line.toString());
            } catch (Exception ignored) {
            }
        }
    }
    private static String sanitize(String input) {
        if (input == null || input.isEmpty()) {
            return "";
        }
        String sanitized = input;
        sanitized = KEY_VALUE_PATTERN.matcher(sanitized).replaceAll("$1=***");
        sanitized = QUOTED_SECRET_PATTERN.matcher(sanitized).replaceAll("$1 \"***\"");
        return sanitized;
    }

    private static File getLogFile(Context context) {
        return new File(new File(context.getCacheDir(), LOG_DIR), LOG_FILE);
    }

    private static String timestamp() {
        return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(new Date());
    }

    private static void writeLine(FileOutputStream out, String line) throws IOException {
        out.write((line + "\n").getBytes(StandardCharsets.UTF_8));
    }

    private static void writeSafeLine(FileOutputStream out, String line) {
        try {
            writeLine(out, line);
        } catch (Exception ignored) {
        }
    }

    private static String describeSocks5Upstream(LocalSettings settings) {
        if (!settings.isSocks5Enabled() || !settings.isSocks5UpstreamEnabled() || settings.getSocks5UpstreamHost().isBlank()) {
            return "disabled";
        }
        return settings.getSocks5UpstreamProtocol()
            + " "
            + settings.getSocks5UpstreamHost()
            + ":"
            + settings.getSocks5UpstreamPort();
    }

    private static String describeHttpUpstream(LocalSettings settings) {
        if (!settings.isHttpEnabled() || !settings.isHttpUpstreamEnabled() || settings.getHttpUpstreamHost().isBlank()) {
            return "disabled";
        }
        return settings.getHttpUpstreamProtocol()
            + " "
            + settings.getHttpUpstreamHost()
            + ":"
            + settings.getHttpUpstreamPort();
    }

    private static String describeActiveNetwork(Context context) {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) {
                return "unknown";
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                Network network = cm.getActiveNetwork();
                if (network == null) {
                    return "offline";
                }
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null) {
                    return "unknown";
                }
                String transport = caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                    ? "wifi"
                    : caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
                        ? "cellular"
                        : caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
                            ? "ethernet"
                            : caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                                ? "vpn"
                                : "other";
                String metered = cm.isActiveNetworkMetered() ? "metered" : "unmetered";
                return transport + ", " + metered;
            }
            android.net.NetworkInfo info = cm.getActiveNetworkInfo();
            if (info == null || !info.isConnected()) {
                return "offline";
            }
            String type = info.getTypeName() != null ? info.getTypeName().toLowerCase(Locale.US) : "unknown";
            return type + ", legacy";
        } catch (Exception e) {
            return "unknown (" + e.getClass().getSimpleName() + ")";
        }
    }
}
