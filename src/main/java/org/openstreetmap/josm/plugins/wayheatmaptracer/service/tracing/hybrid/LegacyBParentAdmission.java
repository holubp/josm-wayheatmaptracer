package org.openstreetmap.josm.plugins.wayheatmaptracer.service.tracing.hybrid;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/** Deterministic attempt-wide parent allocation without comparing unrelated detector objectives. */
public final class LegacyBParentAdmission {
    public static final int PARENT_LIMIT = 2;
    private LegacyBParentAdmission() { }

    /** Sources are supplied in selected/native, complete aggregate, configured-alternative order. */
    public record Source(String sourceTier, List<String> nativeParentIds) {
        public Source {
            nativeParentIds = List.copyOf(nativeParentIds);
            if (sourceTier == null || sourceTier.isBlank()
                    || nativeParentIds.stream().anyMatch(id -> id == null || id.isBlank())
                    || new HashSet<>(nativeParentIds).size() != nativeParentIds.size())
                throw new IllegalArgumentException("Source admission requires unique local native parent identities");
        }
    }

    /** Empty admission preserves discovery evidence but grants no B invocation or final candidate. */
    public record Admission(int sourceIndex, String sourceTier, List<String> admittedParentIds, int omittedParents) {
        public Admission {
            admittedParentIds = List.copyOf(admittedParentIds);
            if (sourceIndex < 0 || sourceTier == null || sourceTier.isBlank() || omittedParents < 0
                    || admittedParentIds.size() > PARENT_LIMIT
                    || new HashSet<>(admittedParentIds).size() != admittedParentIds.size())
                throw new IllegalArgumentException("Invalid source-local parent allocation");
        }
    }

    /** Gives source representatives priority, then fills spare capacity in source/native-rank order. */
    public static List<Admission> select(List<Source> sources) {
        sources = List.copyOf(sources);
        if (sources.stream().map(Source::sourceTier).distinct().count() != sources.size())
            throw new IllegalArgumentException("Source tiers must be unique within an attempt");
        var selected = new ArrayList<List<String>>();
        for (int index = 0; index < sources.size(); index++) selected.add(new ArrayList<>());
        int retained = 0;
        for (int index = 0; index < sources.size() && retained < PARENT_LIMIT; index++) {
            if (sources.get(index).nativeParentIds().isEmpty()) continue;
            selected.get(index).add(sources.get(index).nativeParentIds().get(0)); retained++;
        }
        for (int index = 0; index < sources.size() && retained < PARENT_LIMIT; index++) {
            var ids = sources.get(index).nativeParentIds();
            for (int rank = 1; rank < ids.size() && retained < PARENT_LIMIT; rank++) {
                selected.get(index).add(ids.get(rank)); retained++;
            }
        }
        var result = new ArrayList<Admission>();
        for (int index = 0; index < sources.size(); index++) {
            var source = sources.get(index);
            result.add(new Admission(index, source.sourceTier(), selected.get(index),
                    source.nativeParentIds().size() - selected.get(index).size()));
        }
        return List.copyOf(result);
    }
}
