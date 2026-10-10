package edu.redflag.core;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

/**
 * Manifest-driven feature schema loader.
 * Reads the ordered feature names from feature_names.json / feature_columns.json,
 * dynamically indexing all column positions so changes in model training do not break JVM inference.
 */
public final class FeatureSchema {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    public final int size;
    public final List<String> columnNames;
    private final Map<String, Integer> nameToIndex;

    // Fast cached indices for numeric and rolling features
    public final int idxDt;
    public final int idxBytes;
    public final int idxPkts;
    public final int idxFlgsNum;
    public final int idxProtoNum;
    public final int idxStateNum;

    // Window arrays: index 0 -> W=10, 1 -> W=20, 2 -> W=30, 3 -> W=50
    public final int[] idxMeanDt = new int[4];
    public final int[] idxStdDt  = new int[4];
    public final int[] idxMaxDt  = new int[4];
    public final int[] idxMeanBytes = new int[4];
    public final int[] idxStdBytes  = new int[4];
    public final int[] idxPktCount  = new int[4];

    public final Map<String, Integer> protoIndices = new HashMap<>();
    public final Map<String, Integer> stateIndices = new HashMap<>();
    public final Map<String, Integer> flagIndices  = new HashMap<>();

    public final int protoOther;
    public final int stateOther;
    public final int flagOther;

    public FeatureSchema(Path featureManifestPath) throws IOException {
        String[] rawNames = MAPPER.readValue(featureManifestPath.toFile(), String[].class);
        this.size = rawNames.length;
        this.columnNames = Collections.unmodifiableList(Arrays.asList(rawNames));

        Map<String, Integer> map = new HashMap<>(size * 2);
        for (int i = 0; i < size; i++) {
            map.put(rawNames[i], i);
        }
        this.nameToIndex = Collections.unmodifiableMap(map);

        this.idxDt = opt("delta_t");
        this.idxBytes = opt("bytes");
        this.idxPkts = opt("pkts");
        this.idxFlgsNum = opt("flgs_number");
        this.idxProtoNum = opt("proto_number");
        this.idxStateNum = opt("state_number");

        int[] ws = NodeSession.WINDOWS;
        for (int w = 0; w < ws.length; w++) {
            idxMeanDt[w] = opt("delta_t_mean_" + ws[w]);
            idxStdDt[w]  = opt("delta_t_std_"  + ws[w]);
            idxMaxDt[w]  = opt("delta_t_max_"  + ws[w]);
            idxMeanBytes[w] = opt("bytes_mean_" + ws[w]);
            idxStdBytes[w]  = opt("bytes_std_"  + ws[w]);
            idxPktCount[w]  = opt("pkt_count_"  + ws[w]);
        }

        // Categorical prefix indexing
        for (int i = 0; i < size; i++) {
            String col = rawNames[i];
            if (col.startsWith("proto_")) {
                protoIndices.put(col.substring(6).toLowerCase(Locale.ROOT), i);
            } else if (col.startsWith("state_")) {
                stateIndices.put(col.substring(6), i);
            } else if (col.startsWith("flgs_")) {
                flagIndices.put(col.substring(5), i);
            }
        }

        protoOther = opt("proto_other");
        stateOther = opt("state_other");
        flagOther  = opt("flgs_other");
    }

    private int opt(String name) {
        Integer val = nameToIndex.get(name);
        return (val != null) ? val : -1;
    }

    public int indexOf(String name) {
        return opt(name);
    }
}
