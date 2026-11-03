package io.freemytv;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.LinkAddress;
import android.net.LinkProperties;
import android.net.Network;
import android.net.NetworkCapabilities;

import java.io.ByteArrayOutputStream;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** IP addresses shown on the home screen. Call both methods off the UI thread. */
final class IpAddresses {
    private IpAddresses() {}
    private static volatile byte[] countryRanges;

    static String local(Context context) {
        ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return "";
        try {
            String ipv6 = "";
            for (Network network : cm.getAllNetworks()) {
                NetworkCapabilities caps = cm.getNetworkCapabilities(network);
                if (caps == null || caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
                        || !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
                        && !caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) continue;
                LinkProperties links = cm.getLinkProperties(network);
                if (links == null) continue;
                for (LinkAddress link : links.getLinkAddresses()) {
                    InetAddress address = link.getAddress();
                    if (address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    if (address instanceof Inet4Address) return address.getHostAddress();
                    if (ipv6.isEmpty()) ipv6 = address.getHostAddress();
                }
            }
            return ipv6;
        } catch (RuntimeException ignored) {
            return "";
        }
    }

    /** The address seen by websites on the current default route (including VPN when active). */
    static String external() {
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL("https://api.ipify.org").openConnection();
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);
            try (BufferedReader in = new BufferedReader(new InputStreamReader(
                    connection.getInputStream(), StandardCharsets.UTF_8))) {
                String address = in.readLine();
                return address != null && address.matches("([0-9]{1,3}\\.){3}[0-9]{1,3}")
                        ? address : "";
            }
        } catch (Exception ignored) {
            return "";
        } finally {
            if (connection != null) connection.disconnect();
        }
    }

    /** Offline lookup: no additional network request reveals the TV's public address. */
    static String country(Context context, String externalIp) {
        if (externalIp == null || !externalIp.matches("([0-9]{1,3}\\.){3}[0-9]{1,3}")) return "";
        try {
            long ip = 0;
            for (String part : externalIp.split("\\.")) {
                int octet = Integer.parseInt(part);
                if (octet > 255) return "";
                ip = (ip << 8) | octet;
            }
            byte[] ranges = countryRanges;
            if (ranges == null) {
                synchronized (IpAddresses.class) {
                    ranges = countryRanges;
                    if (ranges == null) {
                        try (ByteArrayOutputStream out = new ByteArrayOutputStream(3_700_000);
                             java.io.InputStream in = context.getAssets().open("geoip-country-v4.bin")) {
                            byte[] buffer = new byte[32_768];
                            int n;
                            while ((n = in.read(buffer)) != -1) out.write(buffer, 0, n);
                            ranges = out.toByteArray();
                        }
                        if (ranges.length % 10 != 0) return "";
                        countryRanges = ranges;
                    }
                }
            }
            int low = 0, high = ranges.length / 10 - 1;
            while (low <= high) {
                int mid = (low + high) >>> 1, at = mid * 10;
                long start = uint(ranges, at), end = uint(ranges, at + 4);
                if (ip < start) high = mid - 1;
                else if (ip > end) low = mid + 1;
                else {
                    String code = "" + (char) ranges[at + 8] + (char) ranges[at + 9];
                    return "ZZ".equals(code) ? "" : code;
                }
            }
            return "";
        } catch (Exception ignored) {
            return "";
        }
    }

    private static long uint(byte[] bytes, int at) {
        return ((long) (bytes[at] & 255) << 24) | ((long) (bytes[at + 1] & 255) << 16)
                | ((long) (bytes[at + 2] & 255) << 8) | (bytes[at + 3] & 255);
    }

    static String flag(String country) {
        if (country == null || !country.matches("[A-Z]{2}")) return "";
        int first = 0x1F1E6 + country.charAt(0) - 'A';
        int second = 0x1F1E6 + country.charAt(1) - 'A';
        return new String(Character.toChars(first)) + new String(Character.toChars(second));
    }
}
