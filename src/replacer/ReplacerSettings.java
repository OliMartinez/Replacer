/*
 * Copyright (c) 2023-2026 Oliver Martínez (Ollie_Jess)
 * GNU General Public License v3.0 or later.
 */
package replacer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Configuration models for furniture replacement, materials, alignment, and randomization. */
final class ReplacerSettings {
    private ReplacerSettings() {}

    /** Material selection scope (whole piece or named subset). */
    static final class Source {
        boolean whole = true;
        List<String> materials = new ArrayList<>();
    }

    /** Material copy configuration from origin to replacement pieces. */
    static final class MaterialCopy {
        boolean toWhole = true;
        Source from = new Source();
        Map<String, Source> toMaterials = new LinkedHashMap<>();
    }

    /** Material matching criteria for "Replace all matching". */
    static final class MaterialSearch {
        boolean whole = true;
        List<String> materials = new ArrayList<>();
    }

    /** Subgroup traversal depth settings when replacing elements inside furniture groups. */
    static final class GroupLevels {
        int levels = 0;
        boolean fromBottom = true;
        boolean wholeAtLimit = true;

        int lastDepth(int groupDepth) {
            return levels <= 0 ? Integer.MAX_VALUE : fromBottom ? Math.max(1, groupDepth - levels + 1) : levels;
        }
    }

    /** 2D plan alignment anchor points: column (-1, 0, 1) and row (-1, 0, 1). */
    static final class Alignment {
        int column = 0;
        int row = 0;
    }

    /** Proportional scaling configuration for unselected dimensions. */
    static final class ProportionalSizes {
        boolean enabled = false;
        int base = -1;
    }

    /** Randomization ranges, distribution modes, and selection weights. */
    static final class RandomRanges {
        static final String[] PROPERTIES = {
            "x", "y", "z", "width", "depth", "height", "angle", "pitch", "roll", "shininess", "light_power"
        };
        static final int MODE_VALUE = 0, MODE_ADD = 1, MODE_PERCENT = 2;

        final boolean[] enabled = new boolean[PROPERTIES.length];
        final int[] modes = {
            MODE_ADD, MODE_ADD, MODE_ADD, MODE_PERCENT, MODE_PERCENT, MODE_PERCENT,
            MODE_ADD, MODE_ADD, MODE_ADD, MODE_VALUE, MODE_PERCENT
        };
        final double[] min = {-5, -5, 0, 80, 80, 80, -15, -5, -5, 0, 50};
        final double[] max = {5, 5, 10, 120, 120, 120, 15, 5, 5, 50, 150};

        boolean keepProportions = true;
        boolean mirror = false;
        boolean pickOnePiece = false;
        double mirrorProbability = 50;
        List<Double> pieceWeights = new ArrayList<>();
        Source shininessTarget = new Source();
        RandomLook look = new RandomLook();

        int indexOf(String property) {
            return Arrays.asList(PROPERTIES).indexOf(property);
        }

        boolean anyEnabled() {
            for (boolean e : enabled) {
                if (e) return true;
            }
            return mirror || look.enabled || pickOnePiece;
        }
    }

    /** Random appearance configuration (colors, textures, and RGB jitter). */
    static final class RandomLook {
        boolean enabled = false;
        boolean fromList = false;
        boolean vary = true;
        boolean sameComponents = false;
        boolean customChoices = false;
        Source target = new Source();
        final int[] componentModes = {RandomRanges.MODE_PERCENT, RandomRanges.MODE_PERCENT, RandomRanges.MODE_PERCENT};
        final double[] componentMin = {90, 90, 90};
        final double[] componentMax = {110, 110, 110};
        final List<Object> choices = new ArrayList<>();
        final List<Double> weights = new ArrayList<>();
        final List<Boolean> varied = new ArrayList<>();

        void ensureDefaultChoices(List<Object> defaults) {
            if (!customChoices || choices.isEmpty()) {
                choices.clear();
                weights.clear();
                varied.clear();
                for (Object choice : defaults) {
                    choices.add(choice);
                    weights.add(1.0);
                    varied.add(Boolean.TRUE);
                }
            }
        }

        boolean isVaried(int index) {
            return vary && choices.get(index) instanceof Integer
                    && (index >= varied.size() || Boolean.TRUE.equals(varied.get(index)));
        }

        RandomLook copy() {
            RandomLook copy = new RandomLook();
            copy.enabled = enabled;
            copy.target = copyOf(target);
            copy.fromList = fromList;
            copy.vary = vary;
            copy.sameComponents = sameComponents;
            System.arraycopy(componentModes, 0, copy.componentModes, 0, 3);
            System.arraycopy(componentMin, 0, copy.componentMin, 0, 3);
            System.arraycopy(componentMax, 0, copy.componentMax, 0, 3);
            copy.choices.addAll(choices);
            copy.weights.addAll(weights);
            copy.varied.addAll(varied);
            copy.customChoices = customChoices;
            return copy;
        }
    }

    static Source copyOf(Source source) {
        Source copy = new Source();
        copy.whole = source.whole;
        copy.materials = new ArrayList<>(source.materials);
        return copy;
    }
}
