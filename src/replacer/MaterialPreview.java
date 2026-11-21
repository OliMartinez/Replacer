/*
 * Copyright (c) 2023-2026 Oliver Martínez (Ollie_Jess)
 * GNU General Public License v3.0 or later.
 */
package replacer;

import com.eteks.sweethome3d.j3d.ModelManager;
import com.eteks.sweethome3d.model.Content;
import com.eteks.sweethome3d.model.HomeMaterial;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.HomeTexture;
import com.eteks.sweethome3d.tools.URLContent;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;
import javax.media.j3d.Appearance;
import javax.media.j3d.BranchGroup;
import javax.media.j3d.Geometry;
import javax.media.j3d.GeometryArray;
import javax.media.j3d.GeometryStripArray;
import javax.media.j3d.Group;
import javax.media.j3d.IndexedGeometryArray;
import javax.media.j3d.IndexedGeometryStripArray;
import javax.media.j3d.IndexedQuadArray;
import javax.media.j3d.IndexedTriangleArray;
import javax.media.j3d.IndexedTriangleFanArray;
import javax.media.j3d.IndexedTriangleStripArray;
import javax.media.j3d.Link;
import javax.media.j3d.Node;
import javax.media.j3d.QuadArray;
import javax.media.j3d.Shape3D;
import javax.media.j3d.SharedGroup;
import javax.media.j3d.Transform3D;
import javax.media.j3d.TransformGroup;
import javax.media.j3d.TriangleArray;
import javax.media.j3d.TriangleFanArray;
import javax.media.j3d.TriangleStripArray;
import javax.vecmath.Point3f;

/**
 * Software 3D/2D rasterizer for material part previews and top-down plan icons.
 */
final class MaterialPreview {
    private static final int HIGHLIGHT = 0xFF6A00, OTHER_PARTS = 0xD3D7DD, NO_APPEARANCE = 0xB9BCC2;
    private static final double YAW = Math.toRadians(-28), PITCH = Math.toRadians(22);
    private static final double[] LIGHT = normalize(-0.45, 0.65, 0.62);
    private static final double[] HALF_VECTOR = normalize(LIGHT[0], LIGHT[1], LIGHT[2] + 1);

    /** Bounded LRU caches for extracted meshes, scaled textures, and rendered preview images. */
    private static final Map<Object, Mesh> MESHES = lruCache(8);
    private static final Map<Object, int[][]> TEXTURES = lruCache(32);
    private static final Map<List<Object>, BufferedImage> IMAGES = lruCache(96);

    private static <K, V> Map<K, V> lruCache(final int maxEntries) {
        return new LinkedHashMap<K, V>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<K, V> eldest) {
                return size() > maxEntries;
            }
        };
    }

    private MaterialPreview() {
    }

    /** Renders the 3D model with the target material highlighted in orange against translucent other parts. */
    static synchronized BufferedImage highlighted(HomePieceOfFurniture piece, String materialName, int size) {
        List<Object> key = Arrays.<Object>asList("H", MaterialUtils.modelKey(piece), materialName, size,
                Arrays.deepToString(piece.getModelRotation()), piece.isModelMirrored(),
                piece.getWidth(), piece.getDepth(), piece.getHeight());
        if (IMAGES.containsKey(key)) {
            return IMAGES.get(key);
        }
        Mesh mesh = mesh(piece);
        BufferedImage image = mesh != null ? draw(mesh, piece, materialName, null, true, size) : null;
        IMAGES.put(key, image);
        return image;
    }

    /** Renders only the geometry belonging to the specified material with its color, texture, and shininess. */
    static synchronized BufferedImage part(HomePieceOfFurniture piece, String materialName, HomeMaterial look, int size) {
        Integer color = look != null ? look.getColor() : null;
        HomeTexture texture = look != null ? look.getTexture() : null;
        Float shininess = look != null ? look.getShininess() : null;
        List<Object> key = Arrays.<Object>asList("P", MaterialUtils.modelKey(piece), materialName, size,
                Arrays.deepToString(piece.getModelRotation()), piece.isModelMirrored(),
                piece.getWidth(), piece.getDepth(), piece.getHeight(),
                color, texture != null ? contentKey(texture.getImage()) : null, shininess);
        if (IMAGES.containsKey(key)) {
            return IMAGES.get(key);
        }
        Mesh mesh = mesh(piece);
        BufferedImage image = mesh != null ? draw(mesh, piece, materialName, look, false, size) : null;
        IMAGES.put(key, image);
        return image;
    }

    private static final class Mesh {
        float[] xyz = new float[9 * 256];
        float[] uv = new float[6 * 256];
        String[] materials = new String[256];
        int count;

        void add(float[] corners, float[] texCoords, String material) {
            if (count == materials.length) {
                xyz = Arrays.copyOf(xyz, xyz.length * 2);
                uv = Arrays.copyOf(uv, uv.length * 2);
                materials = Arrays.copyOf(materials, materials.length * 2);
            }
            System.arraycopy(corners, 0, xyz, count * 9, 9);
            System.arraycopy(texCoords, 0, uv, count * 6, 6);
            materials[count++] = material;
        }
    }

    /** Extracts and caches the triangulated 3D mesh for a piece's model. */
    private static Mesh mesh(HomePieceOfFurniture piece) {
        Content model = piece.getModel();
        if (model == null) {
            return null;
        }
        Object key = MaterialUtils.modelKey(piece);
        if (MESHES.containsKey(key)) {
            return MESHES.get(key);
        }
        Mesh mesh = null;
        try {
            BranchGroup root = ModelManager.getInstance().loadModel(model);
            if (root != null) {
                mesh = new Mesh();
                collect(root, new Transform3D(), mesh);
                if (mesh.count == 0) {
                    mesh = null;
                }
            }
        } catch (IOException | RuntimeException | LinkageError ex) {
            System.err.println("ReplacerPlugin: can't draw the model of " + piece.getName() + ": " + ex);
            mesh = null;
        }
        MESHES.put(key, mesh);
        return mesh;
    }

    /** Recursively collects triangulated geometry from a scene-graph node. */
    private static void collect(Node node, Transform3D parentTransform, Mesh mesh) {
        if (node instanceof Group) {
            Transform3D transform = parentTransform;
            if (node instanceof TransformGroup) {
                Transform3D nodeTransform = new Transform3D();
                ((TransformGroup) node).getTransform(nodeTransform);
                transform = new Transform3D(parentTransform);
                transform.mul(nodeTransform);
            }
            Group group = (Group) node;
            for (int i = 0; i < group.numChildren(); i++) {
                collect(group.getChild(i), transform, mesh);
            }
        } else if (node instanceof Link) {
            SharedGroup sharedGroup = ((Link) node).getSharedGroup();
            if (sharedGroup != null) {
                collect(sharedGroup, parentTransform, mesh);
            }
        } else if (node instanceof Shape3D) {
            Shape3D shape = (Shape3D) node;
            Appearance appearance = shape.getAppearance();
            String material = appearance != null ? appearance.getName() : null;
            for (int i = 0; i < shape.numGeometries(); i++) {
                Geometry geometry = shape.getGeometry(i);
                if (geometry instanceof GeometryArray) {
                    addGeometry((GeometryArray) geometry, parentTransform, material, mesh);
                }
            }
        }
    }

    /** Extracts and transforms vertices and UV coordinates from a GeometryArray. */
    private static void addGeometry(GeometryArray geometry, Transform3D transform, String material, Mesh mesh) {
        int format = geometry.getVertexFormat();
        int vertexCount = geometry.getVertexCount();
        if (vertexCount <= 0 || (format & GeometryArray.USE_NIO_BUFFER) != 0) {
            return;
        }
        boolean hasTexCoords = (format & GeometryArray.TEXTURE_COORDINATE_2) != 0
                && (format & (GeometryArray.TEXTURE_COORDINATE_3 | GeometryArray.TEXTURE_COORDINATE_4)) == 0;
        float[] coords = new float[vertexCount * 3];
        float[] texCoords = hasTexCoords ? new float[vertexCount * 2] : null;
        if ((format & GeometryArray.BY_REFERENCE) != 0) {
            if ((format & GeometryArray.INTERLEAVED) != 0) {
                float[] data = geometry.getInterleavedVertices();
                if (data == null) {
                    return;
                }
                int vertexSize = data.length / vertexCount;
                if (vertexSize < 3) {
                    return;
                }
                for (int i = 0; i < vertexCount; i++) {
                    System.arraycopy(data, i * vertexSize + vertexSize - 3, coords, i * 3, 3);
                    if (texCoords != null) {
                        texCoords[i * 2] = data[i * vertexSize];
                        texCoords[i * 2 + 1] = data[i * vertexSize + 1];
                    }
                }
            } else {
                float[] floatCoords = geometry.getCoordRefFloat();
                double[] doubleCoords = floatCoords == null ? geometry.getCoordRefDouble() : null;
                if (floatCoords == null && doubleCoords == null) {
                    return;
                }
                for (int i = 0; i < coords.length; i++) {
                    coords[i] = floatCoords != null
                            ? (i < floatCoords.length ? floatCoords[i] : 0)
                            : (i < doubleCoords.length ? (float) doubleCoords[i] : 0);
                }
                float[] refTexCoords = texCoords != null ? geometry.getTexCoordRefFloat(0) : null;
                if (refTexCoords != null && refTexCoords.length >= texCoords.length) {
                    System.arraycopy(refTexCoords, 0, texCoords, 0, texCoords.length);
                } else {
                    texCoords = null;
                }
            }
        } else {
            geometry.getCoordinates(0, coords);
            if (texCoords != null) {
                geometry.getTextureCoordinates(0, 0, texCoords);
            }
        }

        Point3f point = new Point3f();
        for (int i = 0; i < vertexCount; i++) {
            point.set(coords[i * 3], coords[i * 3 + 1], coords[i * 3 + 2]);
            transform.transform(point);
            coords[i * 3] = point.x;
            coords[i * 3 + 1] = point.y;
            coords[i * 3 + 2] = point.z;
        }

        int[] coordIndices = null;
        int[] texIndices = null;
        int cornerCount;
        if (geometry instanceof IndexedGeometryArray) {
            IndexedGeometryArray indexed = (IndexedGeometryArray) geometry;
            cornerCount = indexed.getIndexCount();
            coordIndices = new int[cornerCount];
            indexed.getCoordinateIndices(0, coordIndices);
            if (texCoords != null) {
                if ((format & GeometryArray.USE_COORD_INDEX_ONLY) != 0) {
                    texIndices = coordIndices;
                } else {
                    texIndices = new int[cornerCount];
                    indexed.getTextureCoordinateIndices(0, 0, texIndices);
                }
            }
        } else {
            cornerCount = geometry.getValidVertexCount();
        }

        Triangles triangles = new Triangles(coords, texCoords, coordIndices, texIndices, material, mesh);
        if (geometry instanceof TriangleArray || geometry instanceof IndexedTriangleArray) {
            for (int k = 0; k + 2 < cornerCount; k += 3) {
                triangles.add(k, k + 1, k + 2);
            }
        } else if (geometry instanceof QuadArray || geometry instanceof IndexedQuadArray) {
            for (int k = 0; k + 3 < cornerCount; k += 4) {
                triangles.add(k, k + 1, k + 2);
                triangles.add(k, k + 2, k + 3);
            }
        } else if (geometry instanceof IndexedGeometryStripArray || geometry instanceof GeometryStripArray) {
            boolean indexed = geometry instanceof IndexedGeometryStripArray;
            int[] stripCounts = new int[indexed ? ((IndexedGeometryStripArray) geometry).getNumStrips()
                    : ((GeometryStripArray) geometry).getNumStrips()];
            if (indexed) {
                ((IndexedGeometryStripArray) geometry).getStripIndexCounts(stripCounts);
            } else {
                ((GeometryStripArray) geometry).getStripVertexCounts(stripCounts);
            }
            boolean fan = geometry instanceof TriangleFanArray || geometry instanceof IndexedTriangleFanArray;
            boolean strip = geometry instanceof TriangleStripArray || geometry instanceof IndexedTriangleStripArray;
            int start = 0;
            for (int stripCount : stripCounts) {
                for (int j = 0; j + 2 < stripCount && start + j + 2 < cornerCount; j++) {
                    if (fan) {
                        if (j + 2 < stripCount) {
                            triangles.add(start, start + j + 1, start + j + 2);
                        }
                    } else if (strip) {
                        if (j % 2 == 0) {
                            triangles.add(start + j, start + j + 1, start + j + 2);
                        } else {
                            triangles.add(start + j + 1, start + j, start + j + 2);
                        }
                    }
                }
                start += stripCount;
            }
        }
    }

    private static final class Triangles {
        private final float[] coords;
        private final float[] texCoords;
        private final int[] coordIndices;
        private final int[] texIndices;
        private final String material;
        private final Mesh mesh;
        private final float[] corners = new float[9];
        private final float[] uv = new float[6];

        Triangles(float[] coords, float[] texCoords, int[] coordIndices, int[] texIndices, String material, Mesh mesh) {
            this.coords = coords;
            this.texCoords = texCoords;
            this.coordIndices = coordIndices;
            this.texIndices = texIndices;
            this.material = material;
            this.mesh = mesh;
        }

        void add(int k0, int k1, int k2) {
            int[] corner = {k0, k1, k2};
            for (int c = 0; c < 3; c++) {
                int vertex = coordIndices != null ? coordIndices[corner[c]] : corner[c];
                if (vertex < 0 || vertex * 3 + 2 >= coords.length) {
                    return;
                }
                System.arraycopy(coords, vertex * 3, corners, c * 3, 3);
                int texVertex = texIndices != null ? texIndices[corner[c]] : vertex;
                if (texCoords != null && texVertex >= 0 && texVertex * 2 + 1 < texCoords.length) {
                    uv[c * 2] = texCoords[texVertex * 2];
                    uv[c * 2 + 1] = texCoords[texVertex * 2 + 1];
                } else {
                    uv[c * 2] = Float.NaN;
                    uv[c * 2 + 1] = Float.NaN;
                }
            }
            mesh.add(corners, uv, material);
        }
    }

    /** Renders a 3D preview using a software rasterizer with 2x supersampling and z-buffering. */
    private static BufferedImage draw(Mesh mesh, HomePieceOfFurniture piece, String materialName, HomeMaterial look,
            boolean highlight, int size) {
        boolean wholePiece = MaterialUtils.WHOLE_PIECE.equals(materialName);
        boolean[] inPart = new boolean[mesh.count];
        boolean anyInPart = false;
        for (int i = 0; i < mesh.count; i++) {
            inPart[i] = wholePiece || materialName != null && materialName.equals(mesh.materials[i]);
            anyInPart |= inPart[i];
        }
        if (!anyInPart) {
            return null;
        }

        float[] points = transformPoints(mesh, piece);
        double[] angles = !wholePiece ? bestViewAngles(mesh, points, inPart) : new double[]{YAW, PITCH};
        double cosYaw = Math.cos(angles[0]), sinYaw = Math.sin(angles[0]);
        double cosPitch = Math.cos(angles[1]), sinPitch = Math.sin(angles[1]);
        float[] view = new float[points.length];
        for (int i = 0; i < mesh.count * 3; i++) {
            double x = points[i * 3], y = points[i * 3 + 1], z = points[i * 3 + 2];
            double x1 = x * cosYaw + z * sinYaw;
            double z1 = -x * sinYaw + z * cosYaw;
            view[i * 3] = (float) x1;
            view[i * 3 + 1] = (float) (y * cosPitch - z1 * sinPitch);
            view[i * 3 + 2] = (float) (y * sinPitch + z1 * cosPitch);
        }

        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (int t = 0; t < mesh.count; t++) {
            if (highlight || inPart[t]) {
                for (int c = 0; c < 3; c++) {
                    int i = t * 3 + c;
                    minX = Math.min(minX, view[i * 3]);
                    maxX = Math.max(maxX, view[i * 3]);
                    minY = Math.min(minY, view[i * 3 + 1]);
                    maxY = Math.max(maxY, view[i * 3 + 1]);
                }
            }
        }
        int renderSize = size * 2;
        float extent = Math.max(maxX - minX, maxY - minY);
        float pixelsPerCm = extent > 0 ? (renderSize - 4) / extent : 1;
        float offsetX = renderSize / 2f - (minX + maxX) / 2 * pixelsPerCm;
        float offsetY = renderSize / 2f + (minY + maxY) / 2 * pixelsPerCm;

        int[] texture = null;
        int textureWidth = 0, textureHeight = 0;
        float textureCmWidth = 100, textureCmHeight = 100;
        int baseColor = NO_APPEARANCE;
        float shininess = 0;
        if (!highlight && look != null) {
            if (look.getTexture() != null) {
                int[][] image = textureImage(look.getTexture().getImage());
                if (image != null) {
                    textureWidth = image[0][0];
                    textureHeight = image[0][1];
                    texture = image[1];
                    baseColor = image[0][2];
                }
                if (look.getTexture().getWidth() > 0 && look.getTexture().getHeight() > 0) {
                    textureCmWidth = look.getTexture().getWidth();
                    textureCmHeight = look.getTexture().getHeight();
                }
            } else if (look.getColor() != null) {
                baseColor = look.getColor() & 0xFFFFFF;
            }
            shininess = look.getShininess() != null ? look.getShininess() : 0;
        }

        int[] pixels = new int[renderSize * renderSize];
        float[] depth = new float[renderSize * renderSize];
        Arrays.fill(depth, -Float.MAX_VALUE);

        int[] otherPixels = highlight ? new int[pixels.length] : pixels;
        float[] otherDepth = highlight ? new float[depth.length] : depth;
        if (highlight) {
            Arrays.fill(otherDepth, -Float.MAX_VALUE);
        }
        float[] sx = new float[3], sy = new float[3], sz = new float[3];
        for (int t = 0; t < mesh.count; t++) {
            if (!highlight && !inPart[t]) {
                continue;
            }
            for (int c = 0; c < 3; c++) {
                int i = (t * 3 + c) * 3;
                sx[c] = offsetX + view[i] * pixelsPerCm;
                sy[c] = offsetY - view[i + 1] * pixelsPerCm;
                sz[c] = view[i + 2];
            }

            int base = t * 9;
            double[] normal = faceNormal(view, base);
            double diffuse = Math.abs(normal[0] * LIGHT[0] + normal[1] * LIGHT[1] + normal[2] * LIGHT[2]);
            double light = 0.42 + 0.58 * diffuse;
            double specular = shininess > 0
                    ? shininess * Math.pow(Math.abs(normal[0] * HALF_VECTOR[0] + normal[1] * HALF_VECTOR[1]
                            + normal[2] * HALF_VECTOR[2]), 24) : 0;
            int faceColor = highlight ? (inPart[t] ? HIGHLIGHT : OTHER_PARTS) : baseColor;

            float[] u = new float[3], v = new float[3];
            boolean textured = texture != null;
            if (textured) {
                boolean modelTexCoords = !Float.isNaN(mesh.uv[t * 6]) && !Float.isNaN(mesh.uv[t * 6 + 2])
                        && !Float.isNaN(mesh.uv[t * 6 + 4]);
                double[] modelNormal = faceNormal(points, base);
                for (int c = 0; c < 3; c++) {
                    if (modelTexCoords) {
                        u[c] = mesh.uv[t * 6 + c * 2];
                        v[c] = mesh.uv[t * 6 + c * 2 + 1];
                    } else {
                        float px = points[base + c * 3], py = points[base + c * 3 + 1], pz = points[base + c * 3 + 2];
                        double ax = Math.abs(modelNormal[0]), ay = Math.abs(modelNormal[1]), az = Math.abs(modelNormal[2]);
                        float a = ax >= ay && ax >= az ? pz : px;
                        float b = ay >= ax && ay >= az ? pz : py;
                        u[c] = a / textureCmWidth;
                        v[c] = b / textureCmHeight;
                    }
                }
            }
            boolean other = highlight && !inPart[t];
            fillTriangle(other ? otherPixels : pixels, other ? otherDepth : depth, renderSize, sx, sy, sz, faceColor, light, specular,
                    textured ? texture : null, textureWidth, textureHeight, u, v);
        }

        if (highlight) {
            for (int i = 0; i < pixels.length; i++) {
                boolean part = depth[i] > -Float.MAX_VALUE;
                boolean other = otherDepth[i] > -Float.MAX_VALUE;
                if (part && other && otherDepth[i] > depth[i]) {
                    pixels[i] = blend(otherPixels[i], pixels[i], 0.35);
                } else if (!part && other) {
                    pixels[i] = otherPixels[i];
                }
            }
        }
        return downscale(pixels, renderSize, size);
    }

    private static List<Object> materialsKey(HomeMaterial[] materials) {
        if (materials == null || materials.length == 0) {
            return null;
        }
        List<Object> list = new java.util.ArrayList<>(materials.length * 4);
        for (HomeMaterial m : materials) {
            if (m != null) {
                java.util.Collections.addAll(list, m.getName(), m.getColor(),
                        m.getTexture() != null ? contentKey(m.getTexture().getImage()) : null, m.getShininess());
            } else {
                list.add(null);
            }
        }
        return list;
    }

    private static List<Object> planViewKey(List<HomePieceOfFurniture> leaves, int size) {
        List<Object> key = new java.util.ArrayList<>(leaves.size() * 16 + 2);
        key.add("V");
        key.add(size);
        for (HomePieceOfFurniture leaf : leaves) {
            java.util.Collections.addAll(key, MaterialUtils.modelKey(leaf),
                    leaves.size() > 1 ? leaf.getX() - leaves.get(0).getX() : 0f,
                    leaves.size() > 1 ? leaf.getY() - leaves.get(0).getY() : 0f,
                    leaf.getElevation(), leaf.getAngle(), leaf.getPitch(), leaf.getRoll(),
                    leaf.getWidth(), leaf.getDepth(), leaf.getHeight(),
                    leaf.isHorizontallyRotated() ? leaf.getWidthInPlan() : leaf.getWidth(),
                    leaf.isHorizontallyRotated() ? leaf.getDepthInPlan() : leaf.getDepth(),
                    leaf.isModelMirrored(), leaf.getColor(),
                    leaf.getTexture() != null ? contentKey(leaf.getTexture().getImage()) : null,
                    materialsKey(leaf.getModelMaterials()));
        }
        return key;
    }

    /** Returns the cached top-down 2D plan view image if already rendered, or null otherwise. */
    static synchronized BufferedImage cachedPlanView(HomePieceOfFurniture piece, int size) {
        if (piece == null || size <= 0) {
            return null;
        }
        List<HomePieceOfFurniture> leaves = MaterialUtils.leaves(piece);
        return leaves.isEmpty() ? null : IMAGES.get(planViewKey(leaves, size));
    }

    /** Renders a top-down 2D plan view of the piece or group with its current materials and rotations. */
    static synchronized BufferedImage planView(HomePieceOfFurniture piece, int size) {
        if (piece == null || size <= 0) {
            return null;
        }
        List<HomePieceOfFurniture> leaves = MaterialUtils.leaves(piece);
        if (leaves.isEmpty()) {
            return null;
        }
        List<Object> key = planViewKey(leaves, size);
        if (IMAGES.containsKey(key)) {
            return IMAGES.get(key);
        }
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE;
        for (HomePieceOfFurniture leaf : leaves) {
            for (float[] pt : leaf.getPoints()) {
                minX = Math.min(minX, pt[0]);
                maxX = Math.max(maxX, pt[0]);
                minY = Math.min(minY, pt[1]);
                maxY = Math.max(maxY, pt[1]);
            }
        }
        float extent = Math.max(maxX - minX, maxY - minY);
        if (extent <= 0) {
            return null;
        }
        int renderSize = size * 2;
        float scale = (renderSize - 8f) / extent;
        float offX = renderSize / 2f - (minX + maxX) / 2f * scale;
        float offY = renderSize / 2f - (minY + maxY) / 2f * scale;
        int[] pixels = new int[renderSize * renderSize];
        float[] depth = new float[pixels.length];
        Arrays.fill(depth, -Float.MAX_VALUE);
        float[] sx = new float[3], sy = new float[3], sz = new float[3], u = new float[3], v = new float[3];
        Map<String, Object> matValues = new java.util.HashMap<>();
        for (HomePieceOfFurniture leaf : leaves) {
            Mesh m = mesh(leaf);
            if (m == null) {
                Object val = MaterialUtils.valueOf(leaf, MaterialUtils.WHOLE_PIECE, MaterialUtils.APPEARANCE);
                int col = val instanceof Integer ? (Integer) val & 0xFFFFFF : OTHER_PARTS;
                float[][] p = leaf.getPoints();
                for (int[] tri : new int[][]{{0, 1, 2}, {0, 2, 3}}) {
                    for (int c = 0; c < 3; c++) {
                        sx[c] = offX + p[tri[c]][0] * scale;
                        sy[c] = offY + p[tri[c]][1] * scale;
                        sz[c] = leaf.getElevation();
                    }
                    fillTriangle(pixels, depth, renderSize, sx, sy, sz, col, 0.9, 0, null, 0, 0, u, v);
                }
                continue;
            }
            matValues.clear();
            float[] pts = transformPoints(m, leaf);
            float cos = (float) Math.cos(leaf.getAngle()), sin = (float) Math.sin(leaf.getAngle());
            float lx = leaf.getX(), ly = leaf.getY(), lz = leaf.getElevation();
            for (int t = 0; t < m.count; t++) {
                int base = t * 9;
                String matName = m.materials[t] != null ? m.materials[t] : MaterialUtils.WHOLE_PIECE;
                Object val = matValues.computeIfAbsent(matName,
                        k -> MaterialUtils.valueOf(leaf, k, MaterialUtils.APPEARANCE));
                HomeTexture tex = val instanceof HomeTexture ? (HomeTexture) val : null;
                int[][] texImg = tex != null ? textureImage(tex.getImage()) : null;
                int color = texImg != null ? texImg[0][2] : val instanceof Integer ? (Integer) val & 0xFFFFFF : OTHER_PARTS;
                float tw = tex != null && tex.getWidth() > 0 ? tex.getWidth() : 100f;
                float th = tex != null && tex.getHeight() > 0 ? tex.getHeight() : 100f;
                for (int c = 0; c < 3; c++) {
                    float px = pts[base + c * 3], py = pts[base + c * 3 + 1], pz = pts[base + c * 3 + 2];
                    sx[c] = offX + (lx + px * cos - pz * sin) * scale;
                    sy[c] = offY + (ly + px * sin + pz * cos) * scale;
                    sz[c] = lz + py;
                    u[c] = !Float.isNaN(m.uv[t * 6 + c * 2]) ? m.uv[t * 6 + c * 2] : px / tw;
                    v[c] = !Float.isNaN(m.uv[t * 6 + c * 2 + 1]) ? m.uv[t * 6 + c * 2 + 1] : pz / th;
                }
                double light = 0.55 + 0.45 * Math.abs(faceNormal(pts, base)[1]);
                fillTriangle(pixels, depth, renderSize, sx, sy, sz, color, light, 0,
                        texImg != null ? texImg[1] : null, texImg != null ? texImg[0][0] : 0,
                        texImg != null ? texImg[0][1] : 0, u, v);
            }
        }
        for (int y = 1; y < renderSize - 1; y++) {
            for (int x = 1; x < renderSize - 1; x++) {
                int idx = y * renderSize + x;
                if (pixels[idx] != 0 && (pixels[idx - 1] == 0 || pixels[idx + 1] == 0
                        || pixels[idx - renderSize] == 0 || pixels[idx + renderSize] == 0)) {
                    pixels[idx] = 0xFF2B2D30;
                }
            }
        }
        BufferedImage image = downscale(pixels, renderSize, size);
        IMAGES.put(key, image);
        return image;
    }

    /** Selects the camera yaw and pitch that maximize visible pixels of the target material part. */
    private static double[] bestViewAngles(Mesh mesh, float[] points, boolean[] inPart) {
        final int grid = 48;
        float minX = Float.MAX_VALUE, minY = Float.MAX_VALUE, minZ = Float.MAX_VALUE;
        float maxX = -Float.MAX_VALUE, maxY = -Float.MAX_VALUE, maxZ = -Float.MAX_VALUE;
        for (int i = 0; i < mesh.count * 3; i++) {
            float x = points[i * 3], y = points[i * 3 + 1], z = points[i * 3 + 2];
            minX = Math.min(minX, x); maxX = Math.max(maxX, x);
            minY = Math.min(minY, y); maxY = Math.max(maxY, y);
            minZ = Math.min(minZ, z); maxZ = Math.max(maxZ, z);
        }
        float extent = Math.max(maxX - minX, Math.max(maxY - minY, maxZ - minZ));
        if (extent <= 0) {
            return new double[]{YAW, PITCH};
        }
        float scale = (grid - 4f) / extent;
        float zTol = extent * 0.01f;
        double[][] candidates = {
            {YAW, PITCH},
            {Math.toRadians(28), PITCH},
            {YAW + Math.PI / 2, PITCH},
            {YAW - Math.PI / 2, PITCH},
            {YAW + Math.PI, PITCH},
            {Math.toRadians(180 + 28), PITCH},
            {Math.toRadians(-90), PITCH},
            {Math.toRadians(90), PITCH},
            {Math.toRadians(180), PITCH},
            {YAW, Math.toRadians(-25)},
            {YAW + Math.PI, Math.toRadians(-25)}
        };
        int[] visible = new int[candidates.length];
        int[] projected = new int[candidates.length];
        int maxProjected = 0;
        float[] partZ = new float[grid * grid];
        float[] otherZ = new float[grid * grid];
        float[] sx = new float[3], sy = new float[3], sz = new float[3];
        for (int c = 0; c < candidates.length; c++) {
            double cosYaw = Math.cos(candidates[c][0]), sinYaw = Math.sin(candidates[c][0]);
            double cosPitch = Math.cos(candidates[c][1]), sinPitch = Math.sin(candidates[c][1]);
            Arrays.fill(partZ, -Float.MAX_VALUE);
            Arrays.fill(otherZ, -Float.MAX_VALUE);
            for (int t = 0; t < mesh.count; t++) {
                for (int v = 0; v < 3; v++) {
                    int idx = (t * 3 + v) * 3;
                    double x = points[idx], y = points[idx + 1], z = points[idx + 2];
                    double x1 = x * cosYaw + z * sinYaw;
                    double z1 = -x * sinYaw + z * cosYaw;
                    sx[v] = grid / 2f + (float) x1 * scale;
                    sy[v] = grid / 2f - (float) (y * cosPitch - z1 * sinPitch) * scale;
                    sz[v] = (float) (y * sinPitch + z1 * cosPitch);
                }
                fillTriangle(null, inPart[t] ? partZ : otherZ, grid, sx, sy, sz, 0, 0, 0, null, 0, 0, null, null);
            }
            int vis = 0, proj = 0;
            for (int i = 0; i < grid * grid; i++) {
                if (partZ[i] > -Float.MAX_VALUE) {
                    proj++;
                    if (partZ[i] >= otherZ[i] - zTol) {
                        vis++;
                    }
                }
            }
            visible[c] = vis;
            projected[c] = proj;
            maxProjected = Math.max(maxProjected, proj);
        }
        int best = 0;
        for (int c = 1; c < candidates.length; c++) {
            if (visible[c] > visible[best]) {
                best = c;
            }
        }

        if (maxProjected == 0
                || visible[best] <= 2
                || visible[best] < projected[best] * 0.15
                || (visible[0] >= projected[0] * 0.45 && visible[best] <= visible[0] * 1.35)) {
            return candidates[0];
        }
        return candidates[best];
    }

    /** Transforms mesh vertices by model rotation, piece dimensions, mirroring, and horizontal pitch/roll. */
    private static float[] transformPoints(Mesh mesh, HomePieceOfFurniture piece) {
        float[][] rotation = piece.getModelRotation();
        float[] points = new float[mesh.count * 9];
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE};
        float[] max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < mesh.count * 3; i++) {
            float x = mesh.xyz[i * 3], y = mesh.xyz[i * 3 + 1], z = mesh.xyz[i * 3 + 2];
            for (int axis = 0; axis < 3; axis++) {
                float v = rotation != null
                        ? rotation[axis][0] * x + rotation[axis][1] * y + rotation[axis][2] * z
                        : axis == 0 ? x : axis == 1 ? y : z;
                points[i * 3 + axis] = v;
                min[axis] = Math.min(min[axis], v);
                max[axis] = Math.max(max[axis], v);
            }
        }
        float[] scale = {
            scaleOf(piece.getWidth(), max[0] - min[0]),
            scaleOf(piece.getHeight(), max[1] - min[1]),
            scaleOf(piece.getDepth(), max[2] - min[2])};
        boolean horizRot = piece.isHorizontallyRotated();
        float cp = horizRot ? (float) Math.cos(piece.getPitch()) : 1f;
        float sp = horizRot ? (float) Math.sin(piece.getPitch()) : 0f;
        float cr = horizRot ? (float) Math.cos(piece.getRoll()) : 1f;
        float sr = horizRot ? (float) Math.sin(piece.getRoll()) : 0f;
        for (int i = 0; i < mesh.count * 3; i++) {
            for (int axis = 0; axis < 3; axis++) {
                points[i * 3 + axis] = (points[i * 3 + axis] - (min[axis] + max[axis]) / 2) * scale[axis];
            }
            if (piece.isModelMirrored()) {
                points[i * 3] = -points[i * 3];
            }
            if (horizRot) {
                float x = points[i * 3], y = points[i * 3 + 1], z = points[i * 3 + 2];
                float x1 = x * cr - y * sr;
                float y1 = x * sr + y * cr;
                points[i * 3] = x1;
                points[i * 3 + 1] = y1 * cp - z * sp;
                points[i * 3 + 2] = y1 * sp + z * cp;
            }
        }
        return points;
    }

    private static double[] faceNormal(float[] a, int base) {
        return normalize(
                (a[base + 4] - a[base + 1]) * (a[base + 8] - a[base + 2])
                - (a[base + 5] - a[base + 2]) * (a[base + 7] - a[base + 1]),
                (a[base + 5] - a[base + 2]) * (a[base + 6] - a[base])
                - (a[base + 3] - a[base]) * (a[base + 8] - a[base + 2]),
                (a[base + 3] - a[base]) * (a[base + 7] - a[base + 1])
                - (a[base + 4] - a[base + 1]) * (a[base + 6] - a[base]));
    }

    private static BufferedImage downscale(int[] pixels, int renderSize, int size) {
        BufferedImage large = new BufferedImage(renderSize, renderSize, BufferedImage.TYPE_INT_ARGB);
        large.setRGB(0, 0, renderSize, renderSize, pixels, 0, renderSize);
        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        g.drawImage(large, 0, 0, size, size, null);
        g.dispose();
        return image;
    }

    private static float scaleOf(float pieceSize, float modelSize) {
        return modelSize > 1E-6f && pieceSize > 0 ? pieceSize / modelSize : 1;
    }

    private static void fillTriangle(int[] pixels, float[] depth, int size, float[] x, float[] y, float[] z,
            int color, double light, double specular, int[] texture, int textureWidth, int textureHeight,
            float[] u, float[] v) {
        float area = (x[1] - x[0]) * (y[2] - y[0]) - (x[2] - x[0]) * (y[1] - y[0]);
        if (Math.abs(area) < 1E-6f) {
            return;
        }
        int minX = Math.max(0, (int) Math.floor(Math.min(x[0], Math.min(x[1], x[2]))));
        int maxX = Math.min(size - 1, (int) Math.ceil(Math.max(x[0], Math.max(x[1], x[2]))));
        int minY = Math.max(0, (int) Math.floor(Math.min(y[0], Math.min(y[1], y[2]))));
        int maxY = Math.min(size - 1, (int) Math.ceil(Math.max(y[0], Math.max(y[1], y[2]))));
        int flat = texture == null ? shade(color, light, specular) : 0;
        for (int py = minY; py <= maxY; py++) {
            float cy = py + 0.5f;
            for (int px = minX; px <= maxX; px++) {
                float cx = px + 0.5f;
                float w0 = ((x[2] - x[1]) * (cy - y[1]) - (y[2] - y[1]) * (cx - x[1])) / area;
                float w1 = ((x[0] - x[2]) * (cy - y[2]) - (y[0] - y[2]) * (cx - x[2])) / area;
                float w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) {
                    continue;
                }
                float pz = w0 * z[0] + w1 * z[1] + w2 * z[2];
                int index = py * size + px;
                if (pz <= depth[index]) {
                    continue;
                }
                depth[index] = pz;
                if (pixels == null) {
                    continue;
                } else if (texture == null) {
                    pixels[index] = flat;
                } else {
                    float tu = w0 * u[0] + w1 * u[1] + w2 * u[2];
                    float tv = w0 * v[0] + w1 * v[1] + w2 * v[2];

                    int tx = (int) Math.floor((tu - Math.floor(tu)) * textureWidth);
                    int ty = (int) Math.floor((1 - (tv - Math.floor(tv))) * textureHeight);
                    tx = Math.min(textureWidth - 1, Math.max(0, tx));
                    ty = Math.min(textureHeight - 1, Math.max(0, ty));
                    pixels[index] = shade(texture[ty * textureWidth + tx], light, specular);
                }
            }
        }
    }

    private static int blend(int front, int back, double alpha) {
        int r = (int) (((front >> 16) & 0xFF) * alpha + ((back >> 16) & 0xFF) * (1 - alpha));
        int g = (int) (((front >> 8) & 0xFF) * alpha + ((back >> 8) & 0xFF) * (1 - alpha));
        int b = (int) ((front & 0xFF) * alpha + (back & 0xFF) * (1 - alpha));
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static int shade(int rgb, double light, double specular) {
        int r = (int) Math.min(255, ((rgb >> 16) & 0xFF) * light + 255 * specular);
        int g = (int) Math.min(255, ((rgb >> 8) & 0xFF) * light + 255 * specular);
        int b = (int) Math.min(255, (rgb & 0xFF) * light + 255 * specular);
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    private static double[] normalize(double x, double y, double z) {
        double length = Math.sqrt(x * x + y * y + z * z);
        return length > 0 ? new double[]{x / length, y / length, z / length} : new double[]{0, 0, 1};
    }

    private static Object contentKey(Content content) {
        return content instanceof URLContent ? ((URLContent) content).getURL().toString() : content;
    }

    private static int[][] textureImage(Content content) {
        if (content == null) {
            return null;
        }
        Object key = contentKey(content);
        if (TEXTURES.containsKey(key)) {
            return TEXTURES.get(key);
        }
        int[][] result = null;
        try (InputStream in = content.openStream()) {
            BufferedImage image = ImageIO.read(in);
            if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
                double scale = Math.min(1, 256.0 / Math.max(image.getWidth(), image.getHeight()));
                int width = Math.max(1, (int) Math.round(image.getWidth() * scale));
                int height = Math.max(1, (int) Math.round(image.getHeight() * scale));
                BufferedImage small = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
                Graphics2D g = small.createGraphics();
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g.drawImage(image, 0, 0, width, height, null);
                g.dispose();
                int[] pixels = small.getRGB(0, 0, width, height, null, 0, width);
                long r = 0, gr = 0, b = 0;
                for (int pixel : pixels) {
                    r += (pixel >> 16) & 0xFF;
                    gr += (pixel >> 8) & 0xFF;
                    b += pixel & 0xFF;
                }
                int n = pixels.length;
                int average = (int) (r / n) << 16 | (int) (gr / n) << 8 | (int) (b / n);
                result = new int[][]{{width, height, average}, pixels};
            }
        } catch (IOException | RuntimeException ex) {
        }
        TEXTURES.put(key, result);
        return result;
    }
}
