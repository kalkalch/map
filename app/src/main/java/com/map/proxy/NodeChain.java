// NodeChain.java
package com.map.proxy;

/**
 * Interface for proxy chain nodes.
 * Implements chain of responsibility pattern for traffic forwarding.
 */
public interface NodeChain {
    
    /**
     * Get the next node in the chain.
     */
    NodeChain getNext();
    
    /**
     * Set the next node in the chain.
     */
    void setNext(NodeChain next);
    
    /**
     * Process traffic and forward to destination through this node.
     * @param destination Target address to connect to.
     */
    void process(String destination);
}
