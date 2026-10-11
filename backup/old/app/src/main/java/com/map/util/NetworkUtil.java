// NetworkUtil.java
package com.map.util;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

/**
 * Utility class for network operations and IP management.
 */
public class NetworkUtil {
    
    private static final String LOOPBACK = "127.0.0.1";
    
    /**
     * Get local IP address of the device.
     * Prefers WiFi/Ethernet over mobile data.
     * 
     * @return Local IP address or 127.0.0.1 if not found.
     */
    public static String getLocalIpAddress() {
        try {
            List<NetworkInterface> interfaces = Collections.list(
                NetworkInterface.getNetworkInterfaces()
            );
            
            String wifiAddress = null;
            String mobileAddress = null;
            
            for (NetworkInterface ni : interfaces) {
                if (!ni.isUp() || ni.isLoopback()) {
                    continue;
                }
                
                Enumeration<InetAddress> addresses = ni.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress addr = addresses.nextElement();
                    
                    if (addr.isLoopbackAddress() || !(addr instanceof Inet4Address)) {
                        continue;
                    }
                    
                    String hostAddress = addr.getHostAddress();
                    if (hostAddress == null) {
                        continue;
                    }
                    
                    String name = ni.getName().toLowerCase();
                    
                    // Prefer WiFi (wlan) or Ethernet (eth)
                    if (name.startsWith("wlan") || name.startsWith("eth")) {
                        wifiAddress = hostAddress;
                    } else if (name.startsWith("rmnet") || name.startsWith("pdp")) {
                        // Mobile data interface
                        if (mobileAddress == null) {
                            mobileAddress = hostAddress;
                        }
                    } else if (wifiAddress == null && mobileAddress == null) {
                        // Other interfaces as fallback
                        wifiAddress = hostAddress;
                    }
                }
            }
            
            if (wifiAddress != null) {
                return wifiAddress;
            }
            if (mobileAddress != null) {
                return mobileAddress;
            }
            
        } catch (SocketException e) {
            // Log error if needed
        }
        
        return LOOPBACK;
    }
    
    /**
     * Check if device has active network connection.
     */
    public static boolean hasNetworkConnection() {
        try {
            List<NetworkInterface> interfaces = Collections.list(
                NetworkInterface.getNetworkInterfaces()
            );
            
            for (NetworkInterface ni : interfaces) {
                if (ni.isUp() && !ni.isLoopback()) {
                    return true;
                }
            }
        } catch (SocketException e) {
            // Ignore
        }
        return false;
    }
    
    /**
     * Validate IP address format.
     */
    public static boolean isValidIpAddress(String ip) {
        if (ip == null || ip.isEmpty()) {
            return false;
        }
        
        String[] parts = ip.split("\\.");
        if (parts.length != 4) {
            return false;
        }
        
        try {
            for (String part : parts) {
                int value = Integer.parseInt(part);
                if (value < 0 || value > 255) {
                    return false;
                }
            }
            return true;
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    /**
     * Validate hostname format.
     */
    public static boolean isValidHostname(String hostname) {
        if (hostname == null || hostname.isEmpty() || hostname.length() > 253) {
            return false;
        }
        
        // Simple hostname validation
        return hostname.matches("^[a-zA-Z0-9][a-zA-Z0-9\\-.]*[a-zA-Z0-9]$") 
            || hostname.matches("^[a-zA-Z0-9]$");
    }
}