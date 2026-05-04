# Distributed Load Balancer — Java RMI
### Apache NetBeans Setup & Run Instructions

---

## Project Structure

```
DistributedLoadBalancer/
├── src/
│   └── loadbalancer/
│       ├── NodeInterface.java    ← RMI remote interface (the "contract")
│       ├── NodeImpl.java         ← Node implementation (server + balancer)
│       ├── NodeStatus.java       ← Serializable status snapshot
│       ├── Task.java             ← Serializable task object
│       ├── TaskProcessor.java    ← CPU-intensive computation engine
│       ├── TaskGenerator.java    ← Continuous task producer
│       ├── Monitor.java          ← Live console dashboard
│       ├── NodeLauncher.java     ← MAIN: start N nodes locally
│       └── SingleNodeMain.java   ← MAIN: start one node per machine (LAN)
├── build/                        ← compiled .class files
└── dist/
    └── DistributedLoadBalancer.jar
```

---

## How to Import into Apache NetBeans

1. **File → New Project → Java with Ant → Java Application**
2. Name it `DistributedLoadBalancer`, uncheck "Create Main Class"
3. Right-click `Source Packages` → **New → Java Package** → `loadbalancer`
4. Copy all `.java` files from `src/loadbalancer/` into the new package
5. **Right-click project → Properties → Run** and set:
   - Main Class: `loadbalancer.NodeLauncher`
6. Press **F6** (or Run → Run Project)

---

## Option A — Run All Nodes on One Machine (Easiest)

```bash
# Default: 3 nodes on ports 1100, 1101, 1102
java -jar dist/DistributedLoadBalancer.jar

# Custom node count (e.g. 5 nodes)
java -jar dist/DistributedLoadBalancer.jar 5
```

Or in NetBeans: set Run → Arguments to `3` (or any number), then press F6.

---

## Option B — Run One Node Per Machine (LAN Simulation)

Use `SingleNodeMain` when each physical machine runs its own JVM.

### Machine A (IP: 192.168.1.10)
```bash
java -cp DistributedLoadBalancer.jar loadbalancer.SingleNodeMain \
     Node-1 1100 \
     rmi://192.168.1.11:1101/Node-2 \
     rmi://192.168.1.12:1102/Node-3
```

### Machine B (IP: 192.168.1.11)
```bash
java -cp DistributedLoadBalancer.jar loadbalancer.SingleNodeMain \
     Node-2 1101 \
     rmi://192.168.1.10:1100/Node-1 \
     rmi://192.168.1.12:1102/Node-3
```

### Machine C (IP: 192.168.1.12)
```bash
java -cp DistributedLoadBalancer.jar loadbalancer.SingleNodeMain \
     Node-3 1102 \
     rmi://192.168.1.10:1100/Node-1 \
     rmi://192.168.1.11:1101/Node-2
```

> **Tip:** Start nodes in any order. Unreachable peers are retried every 5 s.

---

## How the System Works

### 1. Startup
- Each node creates its own RMI Registry on a unique port.
- Nodes bind themselves under `rmi://host:port/NodeId`.
- Peers exchange address lists so every node knows every other node.

### 2. Task Submission
- `TaskGenerator` creates tasks at random intervals (0.5 – 2 s) and submits
  them to a randomly chosen node via RMI.

### 3. Dynamic Load Balancing
- When a node receives a task it checks `activeTasks / MAX_THREADS`.
- If load ≥ 75%, it queries all live peers and forwards to the least-loaded one.
- If all peers are also busy, it queues the task locally (no task is dropped).

### 4. Fault Tolerance
- Nodes maintain a cache of live peer stubs, refreshed every 5 s.
- Any peer that throws `RemoteException` is excluded from forwarding until
  the next refresh cycle.
- Task generators remove dead nodes from their target list automatically.

### 5. Console Dashboard (every 5 s)
```
══════════════════════════════════════════════════
  Cluster Status  [12:34:56]
══════════════════════════════════════════════════
  Node-1   | load= 75% | active= 3 | done= 42 | fwd= 5  [████████░░]
  Node-2   | load= 25% | active= 1 | done= 38 | fwd= 2  [███░░░░░░░]
  Node-3   | load=  0% | active= 0 | done= 31 | fwd= 0  [░░░░░░░░░░]
══════════════════════════════════════════════════
```

---

## Configurable Constants (edit in source)

| File              | Constant             | Default | Description                        |
|-------------------|----------------------|---------|------------------------------------|
| `NodeImpl`        | `MAX_THREADS`        | 4       | Thread pool size per node          |
| `NodeImpl`        | `OVERLOAD_THRESHOLD` | 0.75    | Load fraction to trigger forwarding|
| `NodeImpl`        | `PEER_CACHE_TTL_MS`  | 5000    | Peer liveness cache interval (ms)  |
| `NodeLauncher`    | `BASE_PORT`          | 1100    | First port (increments per node)   |
| `TaskGenerator`   | `MIN_DELAY_MS`       | 500     | Min interval between tasks         |
| `TaskGenerator`   | `MAX_DELAY_MS`       | 2000    | Max interval between tasks         |
| `Monitor`         | `INTERVAL_SECONDS`   | 5       | Dashboard refresh rate             |

---

## Task Types

| Type              | Parameter       | Computation                              |
|-------------------|-----------------|------------------------------------------|
| `PRIME_SEARCH`    | upper bound     | Sieve of Eratosthenes up to N            |
| `MATRIX_MULTIPLY` | matrix size N   | Two N×N random matrix multiply (O(N³))   |
| `SORT_ARRAY`      | array size      | Sort N random integers (dual-pivot QS)   |

---

## Firewall Notes (for real LAN deployments)

Open the RMI registry ports (default 1100–1102) on each machine:
```bash
# Linux / macOS
sudo ufw allow 1100:1105/tcp

# Windows
netsh advfirewall firewall add rule name="RMI" dir=in action=allow protocol=TCP localport=1100-1105
```

Also set the hostname property so RMI advertises the correct IP:
```bash
java -Djava.rmi.server.hostname=192.168.1.10 -cp ... loadbalancer.SingleNodeMain ...
```
