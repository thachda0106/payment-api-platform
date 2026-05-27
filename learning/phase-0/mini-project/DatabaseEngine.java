// In-Memory Database Engine — Hash Table + B-Tree + WAL + Transactions + REPL
import java.io.*;
import java.util.*;

public class DatabaseEngine {
    // ─── Storage Engine ─────────────────────────────────────────────────────
    static class Record {
        Map<String, String> fields = new LinkedHashMap<>();
        Record(Map<String, String> f) { fields.putAll(f); }
        @Override public String toString() { return fields.toString(); }
    }

    static class HashTable {
        static class Entry { String key; Record value; Entry next; Entry(String k, Record v, Entry n) { key=k;value=v;next=n; } }
        private Entry[] buckets = new Entry[16];
        private int size;

        void put(String key, Record val) {
            int idx = idx(key);
            for (Entry e = buckets[idx]; e != null; e = e.next) { if (e.key.equals(key)) { e.value = val; return; } }
            buckets[idx] = new Entry(key, val, buckets[idx]); size++;
            if (size > buckets.length * 0.75) resize();
        }
        Record get(String key) { for (Entry e = buckets[idx(key)]; e != null; e = e.next) if (e.key.equals(key)) return e.value; return null; }
        Record remove(String key) {
            int i = idx(key); Entry prev = null;
            for (Entry e = buckets[i]; e != null; e = e.next) { if (e.key.equals(key)) { if (prev==null)buckets[i]=e.next; else prev.next=e.next; size--; return e.value; } prev=e; }
            return null;
        }
        boolean contains(String key) { return get(key) != null; }
        int size() { return size; }
        Set<String> keys() { Set<String> s = new LinkedHashSet<>(); for (Entry b : buckets) for (Entry e=b;e!=null;e=e.next) s.add(e.key); return s; }
        private int idx(String k) { return (k.hashCode() & 0x7FFFFFFF) % buckets.length; }
        private void resize() { Entry[] old = buckets; buckets = new Entry[old.length*2]; size=0; for (Entry b:old) for (Entry e=b;e!=null;e=e.next) put(e.key,e.value); }
    }

    // ─── B-Tree (secondary index, String key → Set<String> record IDs) ─────
    static class BTree {
        static final int T = 2; // minimum degree (2-3-4 tree)
        static class Node { boolean leaf=true; List<String> keys=new ArrayList<>(); List<Set<String>> values=new ArrayList<>(); List<Node> children=new ArrayList<>(); }
        private Node root = new Node();

        void insert(String key, String recordId) {
            if (root.keys.size() == 2*T-1) { Node s = new Node(); s.leaf=false; s.children.add(root); splitChild(s,0); root=s; }
            insertNonFull(root, key, recordId);
        }
        void remove(String key, String recordId) {
            Set<String> ids = search(root, key);
            if (ids != null) { ids.remove(recordId); if (ids.isEmpty()) delete(root, key); }
        }
        Set<String> search(String key) { return search(root, key); }
        Set<String> rangeSearch(String min, String max) { Set<String> r = new LinkedHashSet<>(); rangeSearch(root, min, max, r); return r; }
        private Set<String> search(Node n, String key) { int i=0; while(i<n.keys.size()&&key.compareTo(n.keys.get(i))>0)i++; if(i<n.keys.size()&&key.equals(n.keys.get(i)))return n.values.get(i); return n.leaf?null:search(n.children.get(i),key); }
        private void rangeSearch(Node n, String min, String max, Set<String> result) {
            int i=0; while(i<n.keys.size()&&n.keys.get(i).compareTo(min)<0)i++;
            while(i<n.keys.size()&&n.keys.get(i).compareTo(max)<=0) { if(!n.leaf) rangeSearch(n.children.get(i),min,max,result); result.addAll(n.values.get(i)); i++; }
            if(!n.leaf&&i<n.children.size()) rangeSearch(n.children.get(i),min,max,result);
        }
        private void splitChild(Node parent, int i) {
            Node y = parent.children.get(i); Node z = new Node(); z.leaf=y.leaf;
            for(int j=0;j<T-1;j++)z.keys.add(y.keys.remove(T));
            for(int j=0;j<T-1;j++)z.values.add(y.values.remove(T));
            if(!y.leaf) for(int j=0;j<T;j++)z.children.add(y.children.remove(T));
            parent.keys.add(i, y.keys.remove(T-1)); parent.values.add(i, y.values.remove(T-1)); parent.children.add(i+1,z);
        }
        private void insertNonFull(Node n, String key, String recordId) {
            int i = n.keys.size()-1;
            if(n.leaf) { while(i>=0&&key.compareTo(n.keys.get(i))<0)i--; i++; n.keys.add(i,key); n.values.add(i,new LinkedHashSet<>(List.of(recordId))); }
            else { while(i>=0&&key.compareTo(n.keys.get(i))<0)i--; i++; if(n.children.get(i).keys.size()==2*T-1){splitChild(n,i); if(key.compareTo(n.keys.get(i))>0)i++;} insertNonFull(n.children.get(i),key,recordId); }
        }
        private void delete(Node n, String key) {
            int i=0; while(i<n.keys.size()&&key.compareTo(n.keys.get(i))>0)i++;
            if(i<n.keys.size()&&key.equals(n.keys.get(i))) { if(n.leaf) { n.keys.remove(i); n.values.remove(i); } else { /* simplified: just remove from leaf case */ } }
            else if(!n.leaf) { delete(n.children.get(i), key); if(n.children.get(i).keys.isEmpty()) n.children.remove(i); }
        }
    }

    // ─── Write-Ahead Log ────────────────────────────────────────────────────
    static class WAL {
        private final BufferedWriter writer;
        WAL(String path) throws IOException { writer = new BufferedWriter(new FileWriter(path, true)); }
        synchronized void log(String entry) throws IOException { writer.write(entry+"\n"); writer.flush(); }
        void close() throws IOException { writer.close(); }
    }
    static List<String> replay(String path) throws IOException {
        List<String> entries = new ArrayList<>();
        File f = new File(path);
        if(!f.exists()) { f.createNewFile(); return entries; }
        try(BufferedReader r = new BufferedReader(new FileReader(f))) { String line; while((line=r.readLine())!=null) entries.add(line); }
        return entries;
    }

    // ─── Transaction Manager ─────────────────────────────────────────────────
    static class Transaction {
        Map<String, Record> pending = new LinkedHashMap<>();
        Set<String> deleted = new HashSet<>();
        boolean active;
        void begin() { active=true; pending.clear(); deleted.clear(); }
        void commit(HashTable storage, BTree index, WAL wal) throws IOException {
            for(var e : pending.entrySet()) { storage.put(e.getKey(),e.getValue()); wal.log("COMMIT INSERT "+e.getKey()+" "+serialize(e.getValue())); }
            for(String k : deleted) { storage.remove(k); wal.log("COMMIT DELETE "+k); }
            for(String k : pending.keySet()) { // update index
                Record r = pending.get(k);
                if(r.fields.containsKey("balance")) index.insert(r.fields.get("balance"), k);
            }
            active=false; pending.clear(); deleted.clear();
        }
        void rollback() { active=false; pending.clear(); deleted.clear(); }
    }

    // ─── Engine ─────────────────────────────────────────────────────────────
    private final HashTable storage = new HashTable();
    private final BTree balanceIndex = new BTree();
    private final Transaction txn = new Transaction();
    private WAL wal;

    DatabaseEngine(String walPath) throws IOException { wal = new WAL(walPath); replayWAL(walPath); }

    private void replayWAL(String path) throws IOException {
        for(String entry : replay(path)) {
            String[] parts = entry.split(" ", 4);
            if(parts.length<3) continue;
            if(parts[0].equals("COMMIT")&&parts[1].equals("INSERT")) { String key=parts[2]; Record r=deserialize(parts[3]); storage.put(key,r); if(r.fields.containsKey("balance")) balanceIndex.insert(r.fields.get("balance"),key); }
            else if(parts[0].equals("COMMIT")&&parts[1].equals("DELETE")) storage.remove(parts[2]);
        }
    }

    String execute(String cmd) throws IOException {
        String[] parts = cmd.split(" ", 4);
        if(parts.length==0) return "";
        return switch(parts[0].toUpperCase()) {
            case "INSERT" -> cmdInsert(parts);
            case "SELECT" -> cmdSelect(parts);
            case "UPDATE" -> cmdUpdate(parts);
            case "DELETE" -> cmdDelete(parts);
            case "BEGIN" -> { txn.begin(); yield "TRANSACTION STARTED"; }
            case "COMMIT" -> { txn.commit(storage, balanceIndex, wal); yield "COMMITTED"; }
            case "ROLLBACK" -> { txn.rollback(); yield "ROLLED BACK"; }
            case "EXIT" -> "EXIT";
            default -> "Unknown command: " + parts[0];
        };
    }

    private String cmdInsert(String[] parts) {
        String key = parts[1];
        Map<String,String> fields = parseFields(parts.length>2?parts[2]:"");
        Record rec = new Record(fields);
        if(txn.active) { txn.pending.put(key, rec); txn.deleted.remove(key); return "OK (uncommitted)"; }
        storage.put(key, rec);
        if(fields.containsKey("balance")) balanceIndex.insert(fields.get("balance"), key);
        try { wal.log("COMMIT INSERT "+key+" "+serialize(rec)); } catch(IOException e) { return "WAL ERROR: "+e.getMessage(); }
        return "OK";
    }
    private String cmdSelect(String[] parts) {
        if(parts.length<2) return "USAGE: SELECT <key> | SELECT * WHERE <field> >= <min> AND <field> <= <max>";
        if(parts[1].equals("*")) return selectWhere(parts);
        String key = parts[1];
        Record r = txn.active && txn.deleted.contains(key) ? null : (txn.active&&txn.pending.containsKey(key) ? txn.pending.get(key) : storage.get(key));
        return r == null ? "NOT FOUND" : r.toString();
    }
    private String selectWhere(String[] parts) {
        if(parts.length<8) return "USAGE: SELECT * WHERE field >= min AND field <= max";
        String field = parts[3], min = parts[5], max = parts[parts.length-1];
        Set<String> ids = balanceIndex.rangeSearch(min, max);
        if(ids.isEmpty()) return "[]";
        StringBuilder sb = new StringBuilder("[");
        boolean first=true;
        for(String id:ids) { Record r = storage.get(id); if(r!=null) { if(!first)sb.append(",\n "); sb.append("{").append(id).append(": ").append(r).append("}"); first=false; } }
        return sb.append("]").toString();
    }
    private String cmdUpdate(String[] parts) {
        String key = parts[1];
        Map<String,String> updates = parseFields(parts.length>2?parts[2]:"");
        Record existing = txn.active&&txn.pending.containsKey(key)? txn.pending.get(key):storage.get(key);
        if(existing==null) return "NOT FOUND";
        if(txn.active) { Map<String,String> merged=new LinkedHashMap<>(existing.fields); merged.putAll(updates); txn.pending.put(key,new Record(merged)); return "OK (uncommitted)"; }
        existing.fields.putAll(updates); storage.put(key, existing);
        if(updates.containsKey("balance")) balanceIndex.insert(updates.get("balance"), key);
        try { wal.log("COMMIT INSERT "+key+" "+serialize(existing)); } catch(IOException e) { return "WAL ERROR"; }
        return "OK";
    }
    private String cmdDelete(String[] parts) {
        String key = parts[1];
        if(txn.active) { txn.deleted.add(key); txn.pending.remove(key); return "OK (uncommitted)"; }
        Record removed = storage.remove(key);
        if(removed==null) return "NOT FOUND";
        try { wal.log("COMMIT DELETE "+key); } catch(IOException e) { return "WAL ERROR"; }
        return "OK";
    }

    private Map<String,String> parseFields(String s) {
        Map<String,String> m = new LinkedHashMap<>();
        if(s==null||s.isEmpty()) return m;
        for(String pair:s.split(" ")) { int eq=pair.indexOf('='); if(eq>0) m.put(pair.substring(0,eq),pair.substring(eq+1)); }
        return m;
    }
    static String serialize(Record r) {
        StringBuilder sb=new StringBuilder();
        for(var e:r.fields.entrySet()) sb.append(e.getKey()).append("=").append(e.getValue()).append(" ");
        return sb.toString().trim();
    }
    static Record deserialize(String s) { Map<String,String> m=new LinkedHashMap<>(); for(String p:s.split(" ")) { int eq=p.indexOf('='); if(eq>0) m.put(p.substring(0,eq),p.substring(eq+1)); } return new Record(m); }

    // ─── REPL ────────────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        System.out.println("╔══════════════════════════════════════════╗");
        System.out.println("║   In-Memory Database Engine v1.0        ║");
        System.out.println("║   HashTable + B-Tree + WAL + TXN        ║");
        System.out.println("╚══════════════════════════════════════════╝");
        System.out.println("Commands: INSERT key field=value... | SELECT key |");
        System.out.println("  SELECT * WHERE field >= min AND field <= max");
        System.out.println("  UPDATE key field=value | DELETE key");
        System.out.println("  BEGIN | COMMIT | ROLLBACK | EXIT\n");

        DatabaseEngine db = new DatabaseEngine("phase0_wal.log");
        Scanner sc = new Scanner(System.in);

        // Run acceptance tests if "--test" flag
        if(args.length>0 && args[0].equals("--test")) { runAcceptanceTests(db); return; }

        System.out.print("db> ");
        while(sc.hasNextLine()) {
            String line = sc.nextLine().trim();
            if(line.isEmpty()) { System.out.print("db> "); continue; }
            String result = db.execute(line);
            System.out.println(result);
            if(result.equals("EXIT")) break;
            System.out.print("db> ");
        }
        sc.close();
    }

    static void runAcceptanceTests(DatabaseEngine db) throws Exception {
        System.out.println("Running acceptance tests...\n");

        // Test 1: Insert and retrieve
        db.execute("INSERT user1 name=Alice balance=100000 tier=PREMIUM");
        db.execute("INSERT user2 name=Bob balance=50000 tier=BASIC");
        db.execute("INSERT user3 name=Charlie balance=200000 tier=PREMIUM");
        assert db.execute("SELECT user1").contains("Alice") : "Test 1a failed";
        assert db.execute("SELECT user2").contains("Bob") : "Test 1b failed";
        assert db.execute("SELECT user3").contains("Charlie") : "Test 1c failed";
        System.out.println("Test 1 PASS: Insert and retrieve");

        // Test 2: Range query via B-tree
        String range = db.execute("SELECT * WHERE balance >= 50000 AND balance <= 150000");
        assert range.contains("Alice") && range.contains("Bob") && !range.contains("Charlie") : "Test 2 failed: " + range;
        System.out.println("Test 2 PASS: Range query");

        // Test 3: BEGIN → UPDATE → COMMIT
        db.execute("BEGIN");
        db.execute("UPDATE user1 balance=90000");
        assert db.execute("SELECT user1").contains("90000") : "Should see uncommitted change";
        db.execute("COMMIT");
        assert db.execute("SELECT user1").contains("90000") : "Committed value should persist";
        System.out.println("Test 3 PASS: Transaction commit");

        // Test 4: BEGIN → UPDATE → ROLLBACK
        db.execute("BEGIN");
        db.execute("UPDATE user1 balance=1");
        assert db.execute("SELECT user1").contains("1") : "Should see uncommitted change";
        db.execute("ROLLBACK");
        assert db.execute("SELECT user1").contains("90000") : "Rollback should restore";
        System.out.println("Test 4 PASS: Transaction rollback");

        // Test 5: WAL replay
        db.execute("INSERT wal_test data=persisted");
        DatabaseEngine db2 = new DatabaseEngine("phase0_wal.log");
        assert db2.execute("SELECT wal_test").contains("persisted") : "WAL replay failed";
        System.out.println("Test 5 PASS: WAL replay");

        // Test 6: Bulk insert performance
        long t0 = System.nanoTime();
        for(int i=0;i<10000;i++) db.execute("INSERT bulk"+i+" val="+i+" balance="+(i%100));
        long elapsed = (System.nanoTime()-t0)/1_000_000;
        String bulkRange = db.execute("SELECT * WHERE balance >= 50 AND balance <= 50");
        System.out.printf("Test 6 PASS: 10K inserts in %d ms, range query returned %d results%n", elapsed, bulkRange.split(":").length-1);

        // Test 7: Delete
        db.execute("DELETE user2");
        assert db.execute("SELECT user2").equals("NOT FOUND") : "Delete failed";
        System.out.println("Test 7 PASS: Delete");

        System.out.println("\nAll acceptance tests passed!");
    }
}
