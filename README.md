# Distributed Load Balancer — Java RMI + Swing GUI
### 3-Machine Setup with Hardcoded IPs

---

## Network Configuration (fixed — edit `ClusterConfig.java` to change)

| Node   | IP Address   | RMI Port |
|--------|--------------|----------|
| Node-1 | 10.16.45.42  | 1099     |
| Node-2 | 10.16.45.43  | 1099     |
| Node-3 | 10.16.45.44  | 1099     |

---

## File Summary

| File                 | Purpose |
|----------------------|---------|
| `ClusterConfig.java` | **All hardcoded IPs, ports, and tuning constants** — edit here only |
| `NodeInterface.java` | RMI remote interface |
| `Task.java`          | Serialisable work unit |
| `NodeStatus.java`    | Serialisable status snapshot |
| `TaskProcessor.java` | Heavy CPU workloads (matrix 1200–1800, sort 10–20M, primes 10–50M) |
| `NodeImpl.java`      | RMI server + load balancer + peer discovery |
| `TaskGenerator.java` | 3 sender threads per node, 300ms interval |
| `NodeGUI.java`       | Java Swing real-time dashboard |
| `NodeLauncher.java`  | Main class — auto-detects node identity from IP |

---

## How to Run (3 separate machines)

### Step 1 — Copy the JAR to every machine
```
DistributedLoadBalancer.jar  → copy to the same folder on all 3 machines
```

### Step 2 — Open firewall port 1099 (TCP) on all machines
```bash
# Linux
sudo ufw allow 1099/tcp

# Windows (run as Administrator)
netsh advfirewall firewall add rule name="RMI-LB" ^
    dir=in action=allow protocol=TCP localport=1099
```

### Step 3 — Run on each machine

**Machine A (10.16.45.42 — Node-1):**
```bash
java -Djava.rmi.server.hostname=10.16.45.42 -jar DistributedLoadBalancer.jar
```

**Machine B (10.16.45.43 — Node-2):**
```bash
java -Djava.rmi.server.hostname=10.16.45.43 -jar DistributedLoadBalancer.jar
```

**Machine C (10.16.45.44 — Node-3):**
```bash
java -Djava.rmi.server.hostname=10.16.45.44 -jar DistributedLoadBalancer.jar
```

> Start them in any order. Nodes that can't reach peers at startup will
> automatically reconnect every 8 seconds.

---

## How to Run in Apache NetBeans

1. **File → New Project → Java with Ant → Java Application**, uncheck "Create Main Class"
2. Create package `loadbalancer`, add all `.java` files
3. **Right-click project → Properties:**
   - **Run → Main Class:** `loadbalancer.NodeLauncher`
   - **Run → VM Options:** `-Djava.rmi.server.hostname=10.16.45.42`
     *(change IP for each machine)*
4. Press **F6**

---

## Local Testing (1 machine, loopback aliases)

If you don't have 3 physical machines, create loopback aliases so each
"node" binds to a distinct IP:

**Windows (run 3 separate CMD windows as Administrator):**
```cmd
netsh interface ip add address "Loopback Pseudo-Interface 1" 10.16.45.42 255.255.255.0
netsh interface ip add address "Loopback Pseudo-Interface 1" 10.16.45.43 255.255.255.0
netsh interface ip add address "Loopback Pseudo-Interface 1" 10.16.45.44 255.255.255.0
```
Then open 3 terminals and start each node as above.

**Linux:**
```bash
sudo ip addr add 10.16.45.42/24 dev lo
sudo ip addr add 10.16.45.43/24 dev lo
sudo ip addr add 10.16.45.44/24 dev lo
```

---

## Tuning (`ClusterConfig.java`)

| Constant             | Default | Effect |
|----------------------|---------|--------|
| `MAX_THREADS`        | 6       | Max concurrent tasks per node |
| `OVERLOAD_THRESHOLD` | 0.70    | Load % to trigger forwarding (70%) |
| `PEER_CACHE_TTL_MS`  | 5000    | Peer liveness cache interval |
| `MIN_BURN_MS`        | 1500    | Minimum CPU time per task |

---

## Expected Console Output

```
[08:12:34.123] Node-1   STARTED    pool=6 overload=70% peers=[rmi://10.16.45.43:1099/Node-2, ...]
[08:12:35.441] Node-1   ACCEPT     Task[A1B2C3D4|MATRIX_MULTIPLY|1450|Node-1]  active=1/6 (17%)
[08:12:36.002] Node-1   ACCEPT     Task[E5F6G7H8|SORT_ARRAY|15000000|Node-1]  active=2/6 (33%)
...
[08:12:38.771] Node-1   OFFLOAD    A9B0C1D2  myLoad=83%→Node-2(17%)
[08:12:38.772] Node-2   ACCEPT     Task[A9B0C1D2|...]  active=1/6 (17%)  ★ rcv-from=Node-1
```

## GUI Layout

```
╔══════════════════════════════════════════════════════════════╗
║  Node-1                    Java RMI · 10.16.45.42     ● RUNNING ║
╠═══════════════════════╦══════════════════════════════════════╣
║  THIS NODE (Node-1)   ║  PEER: Node-2                        ║
║  [████████░░]  75%    ║  ● ONLINE  [███░░░░░░░]  30%         ║
║  Active: 4/6          ║  Active: 2/6   Completed: 38         ║
║  Completed: 52        ╠══════════════════════════════════════╣
║  Forwarded: 8         ║  PEER: Node-3                        ║
║  Received: 3          ║  ● ONLINE  [░░░░░░░░░░]   0%         ║
╠═══════════════════════╩══════════════════════════════════════╣
║  TASK LOG                                          [Clear]   ║
║  [08:12:38] Node-1  OFFLOAD  A9B0C1D2 83%→Node-2(17%)       ║
║  [08:12:38] Node-1  DONE     E5F6G7H8  active=3/6 (50%)     ║
╚══════════════════════════════════════════════════════════════╝
```
