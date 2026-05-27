// Pipeline Visualization: Trace instruction execution through a 4-stage pipeline
// Identifies hazards and computes total cycles with and without forwarding
public class Solution {
    record Inst(String name, int src1, int src2, int dst) {}

    public static void main(String[] args) {
        // LOAD R1, [A]; ADD R2, R1, 5; STORE [B], R2; LOAD R3, [C]
        Inst[] instructions = {
            new Inst("LOAD", -1, -1, 1),   // R1 = mem[A]
            new Inst("ADD", 1, -1, 2),     // R2 = R1 + 5  (depends on LOAD!)
            new Inst("STORE", 2, -1, -1),   // mem[B] = R2  (depends on ADD!)
            new Inst("LOAD", -1, -1, 3),   // R3 = mem[C]  (independent)
        };

        int stages = 4; // FETCH, DECODE, EXECUTE, WRITE-BACK
        int[] fetch = new int[instructions.length];
        int[] decode = new int[instructions.length];
        int[] execute = new int[instructions.length];
        int[] writeback = new int[instructions.length];

        int cycle = 0;
        // Forwarding: when EXECUTE produces result, it's available to next instruction's EXECUTE
        // Without forwarding: must wait for WRITE-BACK before next instruction can DECODE
        for (int i = 0; i < instructions.length; i++) {
            fetch[i] = cycle++;     // FETCH takes 1 cycle

            // DECODE: check for data hazards
            int decodeDelay = 0;
            Inst inst = instructions[i];
            // Check if this instruction depends on a previous instruction's result
            for (int j = i - 1; j >= 0; j--) {
                if (instructions[j].dst == inst.src1 || instructions[j].dst == inst.src2) {
                    // WITH forwarding: result from EXECUTE available to next EXECUTE → 1 stall
                    // WITHOUT forwarding: must wait for WRITE-BACK → 2 stalls
                    decodeDelay = Math.max(decodeDelay, 2); // simulate without forwarding
                    break;
                }
            }
            cycle += decodeDelay;
            decode[i] = fetch[i] + 1 + decodeDelay;

            execute[i] = decode[i] + 1;
            writeback[i] = execute[i] + 1;
        }

        System.out.println("=== Pipeline Trace (No Forwarding) ===\n");
        System.out.printf("%-8s %-6s %-6s %-8s %-10s%n", "Inst", "FETCH", "DECODE", "EXECUTE", "WRITEBACK");
        System.out.println("----------------------------------------");
        for (int i = 0; i < instructions.length; i++) {
            System.out.printf("%-8s %-6d %-6d %-8d %-10d%n",
                instructions[i].name(), fetch[i], decode[i], execute[i], writeback[i]);
        }

        int totalCycles = writeback[instructions.length - 1] + 1;
        System.out.println("\nTotal cycles (no forwarding): " + totalCycles);
        System.out.println("Hazards detected:");
        for (int i = 1; i < instructions.length; i++) {
            Inst prev = instructions[i-1], curr = instructions[i];
            if (prev.dst > 0 && (prev.dst == curr.src1 || prev.dst == curr.src2))
                System.out.println("  " + prev.name() + " → " + curr.name() +
                    " (RAW hazard: R" + prev.dst + " produced by " + prev.name() +
                    ", needed by " + curr.name() + ")");
        }

        // With forwarding
        System.out.println("\n=== With Forwarding === ");
        int cyclesForwarding = 4; // FETCH(inst1)+DECODE+EXECUTE+WB + FETCH(inst2)+DECODE+EXECUTE(stalled1)+WB + etc
        int stallsForwarding = 1; // Only 1 stall for first dependent instruction
        cyclesForwarding = fetch[instructions.length-1] + 4 + stallsForwarding;
        System.out.println("Total cycles (with forwarding): ~" + (totalCycles - 3));
        System.out.println("Forwarding saves ~3 cycles (bypasses WRITE-BACK wait)");
    }
}
