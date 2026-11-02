package ro.ubm.sd.common;

/** Un rând din lista nodurilor: identitatea, starea și ultimul semn de viață. */
public final class NodeInfo {
    private volatile NodeAddress address;
    private volatile NodeState state = NodeState.ACTIV;
    private volatile long lastSeen;

    public NodeInfo(NodeAddress address, long lastSeen) {
        this.address = address;
        this.lastSeen = lastSeen;
    }

    public String id() {
        return address.id();
    }

    public NodeAddress address() {
        return address;
    }

    public NodeState state() {
        return state;
    }

    public long lastSeen() {
        return lastSeen;
    }

    /** Actualizează adresa (un nod repornit pe alt port) și semnul de viață. */
    public void seen(NodeAddress address, long now) {
        this.address = address;
        this.lastSeen = now;
        this.state = NodeState.ACTIV;
    }

    public void setState(NodeState state) {
        this.state = state;
    }
}
