// ChainProxy.java
package com.map.proxy;

import java.util.ArrayList;
import java.util.List;

/**
 * Proxy Chain - cascading traffic through multiple proxies.
 * Each node in the chain processes and forwards the request.
 * Supports authentication credentials for each node.
 */
public class ChainProxy {
    private ProxyNode head;
    private final List<ProxyNode> nodes = new ArrayList<>();
    
    /**
     * Add a new proxy to the chain.
     */
    public void addNode(ProxyNode node) {
        if (node == null) {
            return;
        }
        
        nodes.add(node);
        
        if (head == null) {
            head = node;
        } else {
            NodeChain current = head;
            while (current.getNext() != null) {
                current = current.getNext();
            }
            current.setNext(node);
        }
    }
    
    /**
     * Remove a node from the chain by index.
     */
    public void removeNode(int index) {
        if (index < 0 || index >= nodes.size()) {
            return;
        }
        
        nodes.remove(index);
        rebuildChain();
    }
    
    /**
     * Clear all nodes from the chain.
     */
    public void clear() {
        nodes.clear();
        head = null;
    }
    
    /**
     * Get all nodes in the chain.
     */
    public List<ProxyNode> getNodes() {
        return new ArrayList<>(nodes);
    }
    
    /**
     * Get the first node (entry point) of the chain.
     */
    public ProxyNode getHead() {
        return head;
    }
    
    /**
     * Check if chain has any nodes.
     */
    public boolean isEmpty() {
        return head == null;
    }
    
    /**
     * Get number of nodes in the chain.
     */
    public int size() {
        return nodes.size();
    }
    
    /**
     * Forward traffic through the entire chain.
     * @param destination Final destination address.
     * @return true if forwarding was initiated successfully.
     */
    public boolean forwardTraffic(String destination) {
        if (head == null || destination == null || destination.isEmpty()) {
            return false;
        }
        
        NodeChain current = head;
        while (current != null) {
            current.process(destination);
            current = current.getNext();
        }
        
        return true;
    }
    
    /**
     * Rebuild chain links after modification.
     */
    private void rebuildChain() {
        head = null;
        
        for (int i = 0; i < nodes.size(); i++) {
            ProxyNode node = nodes.get(i);
            node.setNext(null);
            
            if (i == 0) {
                head = node;
            } else {
                nodes.get(i - 1).setNext(node);
            }
        }
    }
    
    /**
     * Create a ChainProxy from a list of proxy configurations.
     */
    public static ChainProxy fromList(List<ProxyNode> proxyNodes) {
        ChainProxy chain = new ChainProxy();
        if (proxyNodes != null) {
            for (ProxyNode node : proxyNodes) {
                chain.addNode(node);
            }
        }
        return chain;
    }
}