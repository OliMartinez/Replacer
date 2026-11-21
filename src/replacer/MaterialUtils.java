/*
 * Copyright (c) 2023-2026 Oliver Martínez (Ollie_Jess)
 * GNU General Public License v3.0 or later.
 */
package replacer;

import com.eteks.sweethome3d.j3d.ModelManager;
import com.eteks.sweethome3d.model.Content;
import com.eteks.sweethome3d.model.HomeFurnitureGroup;
import com.eteks.sweethome3d.model.HomeMaterial;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.HomeTexture;
import com.eteks.sweethome3d.tools.URLContent;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.media.j3d.BranchGroup;

/** Utilities for reading, comparing, and applying 3D model materials on furniture. */
final class MaterialUtils {
    static final int APPEARANCE = 0, SHININESS = 1;
    static final String WHOLE_PIECE = "[piece]";

    private static final Map<Object, HomeMaterial[]> DEFAULT_MATERIALS = new LinkedHashMap<Object, HomeMaterial[]>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<Object, HomeMaterial[]> eldest) {
            return size() > 64;
        }
    };

    private MaterialUtils() {
    }

    /** Single material entry (or whole-piece override) and its variants. */
    static final class MaterialEntry {
        final String key, rawName, valueText;
        final HomeMaterial material;
        final List<HomeMaterial> variants = new ArrayList<>();
        final List<String> variantTexts = new ArrayList<>();

        MaterialEntry(String key, String rawName, String valueText, HomeMaterial material) {
            this.key = key;
            this.rawName = rawName;
            this.valueText = valueText;
            this.material = material;
            addVariant(material, valueText);
        }

        void addVariant(HomeMaterial look, String text) {
            if (!variantTexts.contains(text)) {
                variants.add(look);
                variantTexts.add(text);
            }
        }

        boolean isWholePiece() {
            return WHOLE_PIECE.equals(rawName);
        }
    }

    /** Furniture piece with its extracted material entries. */
    static final class PieceMaterials {
        final HomePieceOfFurniture piece;
        final List<MaterialEntry> entries;

        PieceMaterials(HomePieceOfFurniture piece, List<MaterialEntry> entries) {
            this.piece = piece;
            this.entries = entries;
        }
    }

    /** Returns a stable cache key for the 3D model of a furniture piece. */
    static Object modelKey(HomePieceOfFurniture piece) {
        Content model = piece.getModel();
        return model instanceof URLContent ? ((URLContent) model).getURL().toString()
                : model != null ? model : "NAME:" + piece.getName();
    }

    /** Flattens a piece or furniture group into its leaf furniture pieces. */
    static List<HomePieceOfFurniture> leaves(HomePieceOfFurniture piece) {
        List<HomePieceOfFurniture> leaves = new ArrayList<>();
        if (piece instanceof HomeFurnitureGroup) {
            for (HomePieceOfFurniture child : ((HomeFurnitureGroup) piece).getAllFurniture()) {
                if (!(child instanceof HomeFurnitureGroup)) {
                    leaves.add(child);
                }
            }
        } else if (piece != null) {
            leaves.add(piece);
        }
        return leaves;
    }

    /** Loads and caches the default materials defined in a piece's 3D model. */
    static synchronized HomeMaterial[] defaultMaterials(HomePieceOfFurniture piece) {
        if (piece == null || piece instanceof HomeFurnitureGroup || piece.getModel() == null) {
            return null;
        }
        Object key = modelKey(piece);
        if (DEFAULT_MATERIALS.containsKey(key)) {
            return DEFAULT_MATERIALS.get(key);
        }
        HomeMaterial[] materials = null;
        try {
            ModelManager manager = ModelManager.getInstance();
            BranchGroup modelRoot = manager.loadModel(piece.getModel());
            if (modelRoot != null) {
                materials = manager.getMaterials(modelRoot);
            }
        } catch (IOException | RuntimeException | LinkageError ex) {
            System.err.println("ReplacerPlugin: can't read the materials of " + piece.getName() + ": " + ex);
        }
        DEFAULT_MATERIALS.put(key, materials);
        return materials;
    }

    /** Resolves an appearance object (texture takes precedence over solid color). */
    static Object appearance(Integer color, HomeTexture texture) {
        return texture != null ? texture : color;
    }

    /** Collects and deduplicates material entries across furniture pieces or groups. */
    static List<PieceMaterials> pieceMaterialsOf(Collection<? extends HomePieceOfFurniture> pieces, int property,
            boolean byModel) {
        List<HomePieceOfFurniture> allLeaves = new ArrayList<>();
        boolean multiContext = pieces.size() > 1;
        for (HomePieceOfFurniture piece : pieces) {
            multiContext |= piece instanceof HomeFurnitureGroup;
            allLeaves.addAll(leaves(piece));
        }
        multiContext |= allLeaves.size() > 1;

        List<PieceMaterials> result = new ArrayList<>();
        Map<String, PieceMaterials> bySignature = new LinkedHashMap<>();

        for (HomePieceOfFurniture leaf : allLeaves) {
            HomeMaterial[] defaults = defaultMaterials(leaf);
            if (defaults == null) {
                continue;
            }
            String prefix = multiContext && leaf.getName() != null && !leaf.getName().isEmpty()
                    ? leaf.getName() + " - " : "";
            Map<String, MaterialEntry> leafEntries = new LinkedHashMap<>();
            StringBuilder sig = new StringBuilder().append(modelKey(leaf)).append('|');
            if (!byModel) {
                sig.append(leaf.getName() != null ? leaf.getName() : "").append('|');
            }

            boolean wholePiece = property == APPEARANCE
                    ? leaf.getColor() != null || leaf.getTexture() != null
                    : leaf.getShininess() != null && leaf.getShininess() > 0f;
            if (wholePiece) {
                Integer color = leaf.getTexture() == null ? leaf.getColor() : null;
                MaterialEntry entry = new MaterialEntry(prefix + WHOLE_PIECE, WHOLE_PIECE,
                        formatPropertyValue(property, property == APPEARANCE
                                ? appearance(leaf.getColor(), leaf.getTexture()) : leaf.getShininess()),
                        newMaterial(WHOLE_PIECE, null, color, leaf.getTexture(), leaf.getShininess()));
                leafEntries.put(WHOLE_PIECE, entry);
                sig.append(WHOLE_PIECE).append('|');
            }

            for (HomeMaterial material : defaults) {
                if (wholePiece || material == null || material.getName() == null
                        || leafEntries.containsKey(material.getName())) {
                    continue;
                }
                String rawName = material.getName();
                HomeMaterial shown = modifiedMaterial(leaf, rawName);
                HomeMaterial eff = shown != null && (shown.getColor() != null || shown.getTexture() != null) ? shown : material;
                HomeTexture texture = eff.getTexture();
                Integer color = texture == null ? eff.getColor() : null;
                Float shininess = leaf.getShininess() != null ? leaf.getShininess()
                        : (shown != null && shown.getShininess() != null) ? shown.getShininess()
                        : material.getShininess();

                if ((property == APPEARANCE && color == null && texture == null)
                        || (property == SHININESS && (shininess == null || shininess <= 0f))) {
                    continue;
                }
                Object value = property == APPEARANCE ? appearance(color, texture) : shininess;
                String valueText = formatPropertyValue(property, value);
                MaterialEntry entry = new MaterialEntry(prefix + rawName, rawName, valueText,
                        newMaterial(rawName, keyOf(material), color, texture, shininess));
                leafEntries.put(rawName, entry);
                sig.append(rawName);
                if (!byModel) {
                    sig.append('=').append(valueText);
                }
                sig.append(';');
            }

            if (!leafEntries.isEmpty()) {
                String sigKey = sig.toString();
                PieceMaterials shown = bySignature.get(sigKey);
                if (shown == null) {
                    PieceMaterials pm = new PieceMaterials(leaf, new ArrayList<>(leafEntries.values()));
                    bySignature.put(sigKey, pm);
                    result.add(pm);
                } else {
                    for (MaterialEntry entry : shown.entries) {
                        MaterialEntry other = leafEntries.get(entry.rawName);
                        if (other != null) {
                            entry.addVariant(other.material, other.valueText);
                        }
                    }
                }
            }
        }
        return result;
    }

    /** Formats a color, texture, or shininess value for UI display. */
    static String formatPropertyValue(int property, Object value) {
        if (value instanceof Integer) {
            int rgb = (Integer) value & 0xFFFFFF;
            return String.format("#%06X (%d, %d, %d)", rgb, (rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
        } else if (value instanceof HomeTexture) {
            HomeTexture texture = (HomeTexture) value;
            if (texture.getName() != null && !texture.getName().isEmpty()) {
                return texture.getName();
            }
            return texture.getWidth() > 0 && texture.getHeight() > 0
                    ? String.format("%.0f × %.0f cm", texture.getWidth(), texture.getHeight()) : "";
        } else if (property == SHININESS) {
            return value instanceof Float ? Math.round((Float) value * 100f) + " %" : "0 %";
        }
        return "";
    }

    static HomeMaterial modifiedMaterial(HomePieceOfFurniture leaf, String name) {
        return find(leaf.getModelMaterials(), name);
    }

    private static HomeMaterial defaultMaterial(HomePieceOfFurniture leaf, String name) {
        return find(defaultMaterials(leaf), name);
    }

    private static HomeMaterial find(HomeMaterial[] materials, String name) {
        if (materials != null) {
            for (HomeMaterial material : materials) {
                if (material != null && name.equals(material.getName())) {
                    return material;
                }
            }
        }
        return null;
    }

    private static boolean hasMaterial(HomePieceOfFurniture leaf, String name) {
        return WHOLE_PIECE.equals(name) || defaultMaterial(leaf, name) != null || modifiedMaterial(leaf, name) != null;
    }

    /** Resolves a qualified material key to the raw material name recognized by the piece model. */
    private static String resolveMaterialName(HomePieceOfFurniture leaf, String targetName, boolean strictOwner) {
        if (targetName == null) {
            return null;
        }
        if (hasMaterial(leaf, targetName)) {
            return targetName;
        }
        String prefix = (leaf.getName() != null ? leaf.getName() : "") + " - ";
        if (targetName.startsWith(prefix) && hasMaterial(leaf, targetName.substring(prefix.length()))) {
            return targetName.substring(prefix.length());
        }
        int sep = targetName.indexOf(" - ");
        return !strictOwner && sep >= 0 && hasMaterial(leaf, targetName.substring(sep + 3))
                ? targetName.substring(sep + 3) : null;
    }

    private static Object lookupMaterialValue(HomePieceOfFurniture leaf, String rawMatName, Map<String, Object> values) {
        String name = leaf.getName();
        Object v = name != null && !name.isEmpty() ? values.get(name + " - " + rawMatName) : null;
        return v != null ? v : values.get(rawMatName);
    }

    /** Retrieves effective appearance or shininess of a named material on a piece. */
    static Object valueOf(HomePieceOfFurniture piece, String name, int property) {
        Object val = valueOf(piece, name, property, true);
        return val != null ? val : valueOf(piece, name, property, false);
    }

    static Object valueOf(HomePieceOfFurniture piece, String name, int property, boolean strictOwner) {
        for (HomePieceOfFurniture leaf : leaves(piece)) {
            String rawName = resolveMaterialName(leaf, name, strictOwner);
            if (rawName == null) {
                continue;
            }
            Object pieceValue = property == APPEARANCE
                    ? appearance(leaf.getColor(), leaf.getTexture()) : leaf.getShininess();
            if (WHOLE_PIECE.equals(rawName)) {
                if (pieceValue != null) {
                    return pieceValue;
                }
                continue;
            }
            HomeMaterial modified = modifiedMaterial(leaf, rawName);
            HomeMaterial original = defaultMaterial(leaf, rawName);
            if (modified == null && original == null) {
                continue;
            }
            if (pieceValue != null) {
                return pieceValue;
            }
            if (property == APPEARANCE) {
                if (modified != null && (modified.getColor() != null || modified.getTexture() != null)) {
                    return appearance(modified.getColor(), modified.getTexture());
                }
                return original != null ? appearance(original.getColor(), original.getTexture()) : null;
            }
            if (modified != null && modified.getShininess() != null) {
                return modified.getShininess();
            }
            return (original != null && original.getShininess() != null && original.getShininess() > 0f)
                    ? original.getShininess() : null;
        }
        return null;
    }

    /** Sets the whole-piece color, texture, or shininess on a furniture piece or all leaves of a group. */
    static void setPieceValue(HomePieceOfFurniture piece, Object value, int property) {
        for (HomePieceOfFurniture leaf : leaves(piece)) {
            if (property == SHININESS) {
                leaf.setShininess((Float) value);
            } else if (value instanceof HomeTexture) {
                leaf.setColor(null);
                leaf.setTexture((HomeTexture) value);
            } else {
                leaf.setTexture(null);
                leaf.setColor((Integer) value);
            }
        }
    }

    /** Applies a map of material values to all matching materials across a piece or group. */
    static int applyToMaterials(HomePieceOfFurniture piece, Map<String, Object> values, int property) {
        int changed = 0;
        for (HomePieceOfFurniture leaf : leaves(piece)) {
            changed += applyToLeaf(leaf, values, property);
        }
        return changed;
    }

    private static int applyToLeaf(HomePieceOfFurniture leaf, Map<String, Object> values, int property) {
        Object wholeValue = lookupMaterialValue(leaf, WHOLE_PIECE, values);
        if (wholeValue != null) {
            setPieceValue(leaf, wholeValue, property);
            return 1;
        }
        HomeMaterial[] defaults = defaultMaterials(leaf);
        if (defaults == null) {
            return 0;
        }
        boolean concerned = false;
        for (HomeMaterial material : defaults) {
            if (material != null && lookupMaterialValue(leaf, material.getName(), values) != null) {
                concerned = true;
                break;
            }
        }
        if (!concerned) {
            return 0;
        }

        HomeMaterial[] materials = new HomeMaterial[defaults.length];
        for (int i = 0; i < defaults.length; i++) {
            if (defaults[i] != null) {
                materials[i] = modifiedMaterial(leaf, defaults[i].getName());
            }
        }

        if (leaf.getColor() != null || leaf.getTexture() != null) {
            for (int i = 0; i < defaults.length; i++) {
                if (defaults[i] != null) {
                    Float shininess = materials[i] != null ? materials[i].getShininess() : null;
                    materials[i] = newMaterial(defaults[i].getName(), keyOf(defaults[i]),
                            leaf.getTexture() == null ? leaf.getColor() : null, leaf.getTexture(), shininess);
                }
            }
            leaf.setColor(null);
            leaf.setTexture(null);
        }
        if (leaf.getShininess() != null && property == SHININESS) {
            for (int i = 0; i < defaults.length; i++) {
                if (defaults[i] != null && (materials[i] == null || materials[i].getShininess() == null)) {
                    HomeMaterial m = materials[i];
                    materials[i] = newMaterial(defaults[i].getName(), keyOf(defaults[i]),
                            m != null ? m.getColor() : null, m != null ? m.getTexture() : null, leaf.getShininess());
                }
            }
            leaf.setShininess(null);
        }
        int changed = 0;
        for (int i = 0; i < defaults.length; i++) {
            if (defaults[i] == null) {
                continue;
            }
            Object value = lookupMaterialValue(leaf, defaults[i].getName(), values);
            if (value == null) {
                continue;
            }
            HomeMaterial m = materials[i];
            String name = defaults[i].getName(), key = keyOf(defaults[i]);
            Float shininess = m != null ? m.getShininess() : null;
            materials[i] = property == SHININESS
                    ? newMaterial(name, key, m != null ? m.getColor() : null, m != null ? m.getTexture() : null, (Float) value)
                    : newMaterial(name, key, value instanceof Integer ? (Integer) value : null,
                            value instanceof HomeTexture ? (HomeTexture) value : null, shininess);
            changed++;
        }
        leaf.setModelMaterials(materials);
        return changed;
    }

    static HomeMaterial newMaterial(String name, String key, Integer color, HomeTexture texture, Float shininess) {
        if (key != null) {
            try {
                return new HomeMaterial(name, key, color, texture, shininess);
            } catch (LinkageError ignored) {
            }
        }
        return new HomeMaterial(name, color, texture, shininess);
    }

    static String keyOf(HomeMaterial material) {
        try {
            return material.getKey();
        } catch (LinkageError ex) {
            return null;
        }
    }

    /** Builds a map of effective material values keyed by material name across all leaves of a piece. */
    static Map<String, Object> effectiveMaterials(HomePieceOfFurniture piece, int property) {
        Map<String, Object> result = new LinkedHashMap<>();
        List<HomePieceOfFurniture> allLeaves = leaves(piece);
        boolean multi = allLeaves.size() > 1;
        for (HomePieceOfFurniture leaf : allLeaves) {
            String prefix = multi && leaf.getName() != null && !leaf.getName().isEmpty() ? leaf.getName() + " - " : "";
            HomeMaterial[] defaults = defaultMaterials(leaf);
            HomeMaterial[] modified = leaf.getModelMaterials();
            if ((defaults == null || defaults.length == 0) && (modified == null || modified.length == 0)) {
                Object val = valueOf(leaf, WHOLE_PIECE, property, true);
                if (val != null) {
                    result.put(prefix + WHOLE_PIECE, val);
                }
                continue;
            }
            if (defaults != null) {
                for (HomeMaterial m : defaults) {
                    if (m != null && m.getName() != null) {
                        result.put(prefix + m.getName(), valueOf(leaf, m.getName(), property, true));
                    }
                }
            }
            if (modified != null) {
                for (HomeMaterial m : modified) {
                    if (m != null && m.getName() != null && !result.containsKey(prefix + m.getName())) {
                        result.put(prefix + m.getName(), valueOf(leaf, m.getName(), property, true));
                    }
                }
            }
        }
        return result;
    }

    /** Compares two material property values (color, texture, or shininess) within configured tolerances. */
    static boolean sameValue(Object v1, Object v2, int property, float shininessTolerance, boolean textureByImage) {
        if (property == SHININESS) {
            if (v1 == null && v2 == null) {
                return true;
            }
            float s1 = v1 instanceof Number ? ((Number) v1).floatValue() : 0f;
            float s2 = v2 instanceof Number ? ((Number) v2).floatValue() : 0f;
            return Math.abs(s1 - s2) <= Math.max(0.001f, shininessTolerance);
        }
        if (v1 == null || v2 == null) {
            return v1 == v2;
        }
        if (v1 instanceof Integer && v2 instanceof Integer) {
            return ((Integer) v1 & 0xFFFFFF) == ((Integer) v2 & 0xFFFFFF);
        }
        if (v1 instanceof HomeTexture && v2 instanceof HomeTexture) {
            HomeTexture t1 = (HomeTexture) v1, t2 = (HomeTexture) v2;
            if (textureByImage) {
                Content i1 = t1.getImage(), i2 = t2.getImage();
                return i1 instanceof URLContent && i2 instanceof URLContent
                        ? ((URLContent) i1).getURL().toString().equals(((URLContent) i2).getURL().toString())
                        : Objects.equals(i1, i2);
            }
            return Objects.equals(t1.getName(), t2.getName());
        }
        return false;
    }

    /** Computes the default candidate colors and textures for random appearance selection. */
    static List<Object> defaultLookChoices(boolean copyAppearance, ReplacerSettings.MaterialCopy copySettings,
            List<PieceMaterials> originMaterials, List<PieceMaterials> replacementMaterials,
            ReplacerSettings.Source lookTarget) {
        List<Object> result = new ArrayList<>();
        if (!copyAppearance) {
            addSourceLooks(!replacementMaterials.isEmpty() ? lookTarget : null,
                    !replacementMaterials.isEmpty() ? replacementMaterials : originMaterials, result);
        } else if (copySettings == null || copySettings.toWhole || copySettings.toMaterials.isEmpty()) {
            addSourceLooks(copySettings != null ? copySettings.from : null, originMaterials, result);
        } else if (replacementMaterials.isEmpty()) {
            for (ReplacerSettings.Source src : copySettings.toMaterials.values()) {
                addSourceLooks(src, originMaterials, result);
            }
        } else {
            boolean allTarget = lookTarget == null || lookTarget.whole || lookTarget.materials.isEmpty();
            for (PieceMaterials pm : replacementMaterials) {
                for (MaterialEntry entry : pm.entries) {
                    if (allTarget || lookTarget.materials.contains(entry.key)
                            || lookTarget.materials.contains(entry.rawName)) {
                        ReplacerSettings.Source src = copySettings.toMaterials.getOrDefault(
                                entry.key, copySettings.toMaterials.get(entry.rawName));
                        if (src != null) {
                            addSourceLooks(src, originMaterials, result);
                        } else {
                            addEntryLooks(entry, result);
                        }
                    }
                }
            }
        }
        if (result.isEmpty()) {
            addSourceLooks(lookTarget, replacementMaterials, result);
        }
        if (result.isEmpty()) {
            addSourceLooks(null, originMaterials, result);
        }
        return result;
    }

    private static void addSourceLooks(ReplacerSettings.Source src, List<PieceMaterials> pieces, List<Object> result) {
        boolean all = src == null || src.whole || src.materials.isEmpty();
        for (PieceMaterials pm : pieces) {
            for (MaterialEntry entry : pm.entries) {
                if (all || src.materials.contains(entry.key) || src.materials.contains(entry.rawName)) {
                    addEntryLooks(entry, result);
                }
            }
        }
    }

    private static void addEntryLooks(MaterialEntry entry, List<Object> result) {
        for (HomeMaterial material : entry.variants) {
            if (material != null) {
                Object look = appearance(material.getColor(), material.getTexture());
                if (look != null && !containsLook(result, look)) {
                    result.add(look);
                }
            }
        }
    }

    private static boolean containsLook(List<Object> list, Object look) {
        for (Object existing : list) {
            if (sameValue(existing, look, APPEARANCE, 0f, false)
                    && (!(existing instanceof HomeTexture) || sameValue(existing, look, APPEARANCE, 0f, true))) {
                return true;
            }
        }
        return false;
    }
}
