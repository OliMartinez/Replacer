/*
 * Copyright (c) 2023-2026 Oliver Martínez (Ollie_Jess)
 * GNU General Public License v3.0 or later.
 */
package replacer;

import com.eteks.sweethome3d.model.Content;
import com.eteks.sweethome3d.model.HomeMaterial;
import com.eteks.sweethome3d.model.HomePieceOfFurniture;
import com.eteks.sweethome3d.model.HomeTexture;
import com.eteks.sweethome3d.model.UserPreferences;
import com.eteks.sweethome3d.swing.FileContentManager;
import com.eteks.sweethome3d.swing.SwingViewFactory;
import com.eteks.sweethome3d.tools.URLContent;
import com.eteks.sweethome3d.viewcontroller.TextureChoiceController;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Window;
import java.awt.event.KeyEvent;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.BiConsumer;
import java.util.function.BinaryOperator;
import java.util.function.Supplier;
import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.ButtonGroup;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JRadioButton;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTable;
import javax.swing.JViewport;
import javax.swing.KeyStroke;
import javax.swing.ListSelectionModel;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.TableCellRenderer;

/**
 * Modal configuration dialogs for material copying, material search, randomization, group depth,
 * proportional dimensions, and 2D alignment.
 */
final class ReplacerDialogs {
    private static final Color HINT = new Color(105, 112, 122);
    private static final Color TABLE_BORDER = new Color(210, 214, 220);

    private static final int PHOTO = 44;

    private static final int SHOWN_VARIANTS = 3;

    private static final Map<List<Object>, Icon> ICONS = new LinkedHashMap<List<Object>, Icon>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<List<Object>, Icon> eldest) {
            return size() > 128;
        }
    };

    static final ExecutorService PHOTOS = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "Replacer material pictures");
        thread.setDaemon(true);
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private ReplacerDialogs() {
    }

    /** Displays the dialog for mapping source materials to replacement materials. */
    static ReplacerSettings.MaterialCopy showMaterialCopyDialog(Window owner, BinaryOperator<String> t,
            String propertyKey, int property, ReplacerSettings.MaterialCopy current,
            List<MaterialUtils.PieceMaterials> originPieces,
            List<MaterialUtils.PieceMaterials> replacementPieces) {
        String propertyLabel = t.apply(propertyKey, propertyKey).trim();
        JDialog dialog = newDialog(owner, t.apply("materialCopyTitle", "Copy {0}: materials")
                .replace("{0}", propertyLabel.toLowerCase()));
        Runnable relayout = packer(dialog);

        JRadioButton toWhole = new JRadioButton(t.apply("toWholeReplacement", "Entire replacement"));
        JRadioButton toMaterials = new JRadioButton(t.apply("toReplacementMaterials",
                "Specific replacement materials:"));
        radioGroup(toWhole, toMaterials);

        SourceChooser wholeSource = new SourceChooser(t, originPieces, property, 210, false);
        wholeSource.load(current.from);
        wholeSource.onLayout = relayout;

        final Map<MaterialUtils.MaterialEntry, ReplacerSettings.Source> sources = new IdentityHashMap<>();
        MultiPieceTableGroup replacementGroup = new MultiPieceTableGroup(replacementPieces, property, t, true, false);

        for (CheckTableModel model : replacementGroup.models) {
            for (int i = 0; i < model.entries.size(); i++) {
                MaterialUtils.MaterialEntry entry = model.entries.get(i);
                ReplacerSettings.Source saved = current.toMaterials.getOrDefault(
                        entry.key, current.toMaterials.get(entry.rawName));
                model.checked[i] = saved != null;
                ReplacerSettings.Source source = new ReplacerSettings.Source();
                if (saved != null) {
                    source.whole = saved.whole;
                    source.materials = new ArrayList<>(saved.materials);
                } else {
                    for (MaterialUtils.PieceMaterials pm : originPieces) {
                        String matchKey = null;
                        for (MaterialUtils.MaterialEntry originEntry : pm.entries) {
                            if (originEntry.key.equals(entry.key)) {
                                matchKey = originEntry.key;
                                break;
                            }
                            if (matchKey == null && originEntry.rawName.equals(entry.rawName)) {
                                matchKey = originEntry.key;
                            }
                        }
                        if (matchKey != null) {
                            source.whole = false;
                            source.materials.add(matchKey);
                        }
                    }
                }
                sources.put(entry, source);
            }
        }

        SourceChooser materialSource = new SourceChooser(t, originPieces, property, 190, true);
        materialSource.onLayout = relayout;
        JLabel sourceTitle = bold("");
        final MaterialUtils.MaterialEntry[] selectedEntry = {null};
        final CheckTableModel[] selectedModel = {null};
        final int[] selectedRow = {-1};
        replacementGroup.onSelect((model, row) -> {
            if (selectedEntry[0] != null) {
                sources.put(selectedEntry[0], materialSource.save());
            }
            selectedEntry[0] = model.entries.get(row);
            selectedModel[0] = model;
            selectedRow[0] = row;
            sourceTitle.setText(t.apply("sourceOf", "Source of «{0}»:")
                    .replace("{0}", displayName(selectedEntry[0].key, t)));
            materialSource.load(sources.get(selectedEntry[0]));
            replacementGroup.repaint();
        });
        materialSource.onChange = () -> {
            if (selectedEntry[0] != null) {
                sources.put(selectedEntry[0], materialSource.save());

                if (!selectedModel[0].checked[selectedRow[0]]) {
                    selectedModel[0].setValueAt(Boolean.TRUE, selectedRow[0], 0);
                }
                replacementGroup.repaint();
            }
        };

        JPanel wholePanel = new Rows()
                .add(new JLabel(t.apply("takeItFrom", "Source:")))
                .add(wholeSource.panel, 0, 12, true).panel;

        JPanel materialsPanel = new Rows()
                .add(scroll(replacementGroup.containerPanel, 200, 640), 2, 0, true)
                .add(sourceTitle, 10, 0, false)
                .add(materialSource.panel, 2, 12, true).panel;

        JPanel toMaterialsRow = withInfo(toMaterials, t.apply("replacementMaterialsHint",
                "Check the materials to modify, then select one to choose its source."));
        toMaterialsRow.setVisible(!replacementPieces.isEmpty());
        JPanel panel = Rows.dialog()
                .add(bold(t.apply("putItOn", "Apply to:")))
                .add(withInfo(toWhole, replacementPieces.isEmpty() ? t.apply("noReplacementMaterials",
                        "Select a replacement to list its materials.") : null))
                .add(wholePanel, 2, 26, true)
                .add(toMaterialsRow, 8, 0, false)
                .add(materialsPanel, 2, 26, true).panel;

        Runnable update = () -> {
            boolean whole = toWhole.isSelected();
            wholePanel.setVisible(whole);
            materialsPanel.setVisible(!whole);
            materialSource.panel.setVisible(selectedEntry[0] != null);
            sourceTitle.setVisible(selectedEntry[0] != null);
            if (whole) {
                replacementGroup.stopEditing();
            }
            relayout.run();
        };
        toWhole.addActionListener(e -> update.run());
        toMaterials.addActionListener(e -> update.run());
        (current.toWhole || replacementPieces.isEmpty() ? toWhole : toMaterials).setSelected(true);
        replacementGroup.selectFirst();
        update.run();

        return show(dialog, panel, t, () -> {
            wholeSource.tables.stopEditing();
            materialSource.tables.stopEditing();
            replacementGroup.stopEditing();
            if (selectedEntry[0] != null) {
                sources.put(selectedEntry[0], materialSource.save());
            }
            ReplacerSettings.MaterialCopy settings = new ReplacerSettings.MaterialCopy();
            settings.toWhole = toWhole.isSelected();
            settings.from = wholeSource.save();
            for (CheckTableModel model : replacementGroup.models) {
                for (int i = 0; i < model.entries.size(); i++) {
                    if (model.checked[i]) {
                        settings.toMaterials.put(model.entries.get(i).key, sources.get(model.entries.get(i)));
                    }
                }
            }
            if (!settings.toWhole && settings.toMaterials.isEmpty()) {
                settings.toWhole = true;
            }
            return settings;
        });
    }

    private static final class SourceChooser {
        final JRadioButton whole;
        final JRadioButton materials;
        final MultiPieceTableGroup tables;
        final JPanel panel;
        private final JScrollPane scroll;
        private final boolean hasOrigin;
        private boolean loading;
        Runnable onChange = () -> {
        };
        Runnable onLayout = () -> {
        };

        SourceChooser(BinaryOperator<String> t, List<MaterialUtils.PieceMaterials> origin, int property, int height,
                boolean single) {
            this(t, origin, property, height, single,
                    t.apply("fromWholeOriginal", "Entire source object"),
                    t.apply("fromWholeOriginalTip", "Uses its overall value or, if none, the first material that has one."),
                    single ? t.apply("fromOriginalMaterial", "Source material:")
                    : t.apply("fromOriginalMaterials", "Specific source materials:"),
                    single ? null : t.apply("fromOriginalMaterialsTip", "The first checked material that has a value is used."),
                    t.apply("noOriginMaterials", "No source material has this property."));
        }

        SourceChooser(BinaryOperator<String> t, List<MaterialUtils.PieceMaterials> origin, int property, int height,
                boolean single, String wholeText, String wholeTip, String materialsText, String materialsTip,
                String noneText) {
            whole = new JRadioButton(wholeText);
            materials = new JRadioButton(materialsText);
            radioGroup(whole, materials);
            hasOrigin = !origin.isEmpty();
            tables = new MultiPieceTableGroup(origin, property, t, false, single);
            scroll = scroll(tables.containerPanel, height, 600);

            JPanel materialsRow = withInfo(materials, materialsTip);
            materialsRow.setVisible(hasOrigin);
            panel = new Rows()
                    .add(withInfo(whole, hasOrigin ? wholeTip : joinTips(wholeTip, noneText)))
                    .add(materialsRow)
                    .add(scroll, 2, 24, true).panel;

            whole.addActionListener(e -> changed());
            materials.addActionListener(e -> changed());
            tables.onCheckChange(this::changed);
        }

        private void changed() {
            update();
            if (!loading) {
                onChange.run();
            }
        }

        private void update() {
            boolean visible = materials.isSelected();
            if (scroll.isVisible() != visible) {
                scroll.setVisible(visible);
                onLayout.run();
            }
        }

        void load(ReplacerSettings.Source source) {
            loading = true;
            try {
                (source.whole || !hasOrigin ? whole : materials).setSelected(true);
                tables.check(source.materials);
                update();
            } finally {
                loading = false;
            }
        }

        ReplacerSettings.Source save() {
            ReplacerSettings.Source source = new ReplacerSettings.Source();
            source.materials = tables.checkedKeys();

            source.whole = whole.isSelected() || source.materials.isEmpty();
            return source;
        }
    }

    private static String displayName(String name, BinaryOperator<String> t) {
        if (name.endsWith(MaterialUtils.WHOLE_PIECE)) {
            return name.substring(0, name.length() - MaterialUtils.WHOLE_PIECE.length())
                    + t.apply("wholeObject", "Whole object");
        }
        return name;
    }

    /** Displays the dialog for selecting which materials to compare when searching for matches. */
    static ReplacerSettings.MaterialSearch showMaterialSearchDialog(Window owner, BinaryOperator<String> t,
            String propertyKey, int property, ReplacerSettings.MaterialSearch current,
            List<MaterialUtils.PieceMaterials> originPieces, JComponent extra) {
        String propertyLabel = t.apply(propertyKey, propertyKey).trim();
        JDialog dialog = newDialog(owner, t.apply("searchSettingsTitle", "Search settings") + " - " + propertyLabel);
        Runnable relayout = packer(dialog);

        JRadioButton whole = new JRadioButton(t.apply("compareWhole", "Entire object"));
        JRadioButton materials = new JRadioButton(t.apply("compareMaterials", "Specific materials:"));
        radioGroup(whole, materials);

        MultiPieceTableGroup originGroup = new MultiPieceTableGroup(originPieces, property, t, false, false);
        originGroup.check(current.materials);
        JScrollPane tables = scroll(originGroup.containerPanel, 240, 560);

        String wholeTip = t.apply("compareWholeTip", "Overall value, or all modified materials.");
        JPanel materialsRow = withInfo(materials, t.apply("compareMaterialsHint",
                "Each material is compared with the material of the same name on other objects."));
        materialsRow.setVisible(!originPieces.isEmpty());
        Rows rows = Rows.dialog()
                .add(bold(t.apply("compare", "Compare:")))
                .add(withInfo(whole, originPieces.isEmpty()
                        ? joinTips(wholeTip, t.apply("noOriginMaterials", "No source material has this property."))
                        : wholeTip))
                .add(materialsRow)
                .add(tables, 2, 26, true);
        if (extra != null) {
            rows.add(new JSeparator(), 8, 0, false).add(extra, 6, 0, false);
        }
        JPanel panel = rows.panel;

        Runnable update = () -> {
            tables.setVisible(materials.isSelected());
            relayout.run();
        };
        whole.addActionListener(e -> update.run());
        materials.addActionListener(e -> update.run());
        (current.whole || originPieces.isEmpty() ? whole : materials).setSelected(true);
        update.run();

        return show(dialog, panel, t, () -> {
            originGroup.stopEditing();
            ReplacerSettings.MaterialSearch settings = new ReplacerSettings.MaterialSearch();
            settings.whole = whole.isSelected();
            settings.materials = originGroup.checkedKeys();
            if (settings.materials.isEmpty()) {
                settings.whole = true;
            }
            return settings;
        });
    }

    /** Displays the main randomization dialog for numeric ranges, group piece weights, and appearance. */
    static ReplacerSettings.RandomRanges showRandomDialog(Window owner, BinaryOperator<String> t,
            ReplacerSettings.RandomRanges current, List<MaterialUtils.PieceMaterials> replacementMaterials,
            java.util.function.Function<ReplacerSettings.Source, List<Object>> defaultLookChoices,
            UserPreferences preferences, boolean rotatable, boolean hasLight,
            List<HomePieceOfFurniture> objects) {
        JDialog dialog = newDialog(owner, t.apply("randomTitle", "Random values"));
        Runnable relayout = packer(dialog);
        String[] modeNames = randomModeNames(t);
        int n = ReplacerSettings.RandomRanges.PROPERTIES.length;

        boolean hasReplacementMaterials = !replacementMaterials.isEmpty();

        boolean group = objects.size() > 1;
        JCheckBox pick = new JCheckBox(t.apply("randomPickPiece",
                "Pick one object of the group per replaced object"), group && current.pickOnePiece);
        final List<Double> pieceWeights = new ArrayList<>(current.pieceWeights);
        PieceTableModel pieceModel = new PieceTableModel(objects, pieceWeights, new String[]{
            "", t.apply("randomObject", "Object"), t.apply("randomWeight", "Weight"),
            t.apply("randomProbability", "Probability")});
        JTable pieceTable = new JTable(pieceModel);
        pieceTable.setRowHeight(28);
        pieceTable.getTableHeader().setReorderingAllowed(false);
        int[] pieceWidths = {36, 300, 70, 90};
        for (int column = 0; column < pieceWidths.length; column++) {
            pieceTable.getColumnModel().getColumn(column).setPreferredWidth(pieceWidths[column]);
        }
        DefaultTableCellRenderer pieceCentered = new DefaultTableCellRenderer();
        pieceCentered.setHorizontalAlignment(SwingConstants.CENTER);
        pieceTable.getColumnModel().getColumn(3).setCellRenderer(pieceCentered);
        JScrollPane pieceScroll = new JScrollPane(pieceTable);
        pieceScroll.setPreferredSize(new Dimension(500, Math.min(6, Math.max(2, objects.size())) * 28 + 28));
        JPanel pickPanel = new Rows()
                .add(withInfo(pick, t.apply("randomPickPieceHint",
                        "Double weight means twice as likely. Copied properties and random values apply to the picked object.")))
                .add(pieceScroll, 2, 26, false).panel;
        pickPanel.setVisible(group);
        boolean anyRotatable = false;
        for (HomePieceOfFurniture object : objects) {
            anyRotatable |= object.isHorizontallyRotatable();
        }
        final boolean objectRotatable = anyRotatable;
        List<Runnable> rotationRows = new ArrayList<>();
        JCheckBox[] checks = new JCheckBox[n];
        @SuppressWarnings({"unchecked", "rawtypes"})
        JComboBox<String>[] modes = new JComboBox[n];
        JSpinner[] mins = new JSpinner[n];
        JSpinner[] maxs = new JSpinner[n];
        JLabel[] units = new JLabel[n];

        JCheckBox keepProportions = new JCheckBox(t.apply("randomKeepProportions", "Keep proportions"),
                current.keepProportions);
        JPanel keepProportionsRow = withInfo(keepProportions, t.apply("randomKeepProportionsTip",
                "Checked sizes use the same percentage (range of the first one)."));
        Runnable updateProportions = () -> {
            int percentSizes = 0;
            for (String size : new String[]{"width", "depth", "height"}) {
                int k = current.indexOf(size);
                if (checks[k] != null && checks[k].isSelected()
                        && modes[k].getSelectedIndex() == ReplacerSettings.RandomRanges.MODE_PERCENT) {
                    percentSizes++;
                }
            }
            keepProportionsRow.setVisible(percentSizes >= 2);
        };

        JPanel grid = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.gridy = 0;
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(2, 0, 2, 8);
        String[] headers = {t.apply("randomProperty", "Property"), t.apply("randomMode", "Mode"),
            t.apply("randomMin", "Min."), t.apply("randomMax", "Max."), "", t.apply("randomApplyToColumn", "Apply to")};
        for (int col = 0; col < headers.length; col++) {
            c.gridx = col;
            JLabel header = bold(headers[col]);
            header.setVisible(col != 5 || hasReplacementMaterials);
            grid.add(header, c);
        }
        for (int i = 0; i < n; i++) {
            String key = ReplacerSettings.RandomRanges.PROPERTIES[i];
            checks[i] = new JCheckBox(t.apply(key, key).trim(), current.enabled[i]);
            modes[i] = new JComboBox<>(modeNames);
            modes[i].setSelectedIndex(current.modes[i]);
            mins[i] = spinner(new SpinnerNumberModel(current.min[i], -100000.0, 100000.0, 1.0), 6);
            maxs[i] = spinner(new SpinnerNumberModel(current.max[i], -100000.0, 100000.0, 1.0), 6);
            units[i] = new JLabel();

            boolean rotation = key.equals("pitch") || key.equals("roll");
            boolean light = key.equals("light_power");
            final int row = i;
            Runnable updateRow = () -> {
                boolean allowed = rotation ? (rotatable || pick.isSelected() && objectRotatable)
                        : (!light || hasLight);
                if (!allowed) {
                    checks[row].setSelected(false);
                }
                checks[row].setVisible(allowed);
                modes[row].setVisible(allowed);
                mins[row].setVisible(allowed);
                maxs[row].setVisible(allowed);
                units[row].setVisible(allowed);
                boolean on = allowed && checks[row].isSelected();
                modes[row].setEnabled(on);
                mins[row].setEnabled(on);
                maxs[row].setEnabled(on);
                units[row].setEnabled(on);
                units[row].setText(unit(key, modes[row].getSelectedIndex()));
                updateProportions.run();
                relayout.run();
            };
            if (rotation) {
                rotationRows.add(updateRow);
            }
            checks[i].addActionListener(e -> updateRow.run());
            modes[i].addActionListener(e -> updateRow.run());
            updateRow.run();
            addGridRow(grid, c, i + 1, checks[i], modes[i], mins[i], maxs[i], units[i]);
        }

        final ReplacerSettings.Source[] shininessTarget = {ReplacerSettings.copyOf(current.shininessTarget)};
        int shininessRow = current.indexOf("shininess");
        String shininessLabel = t.apply("shininess", "Shininess").trim();
        JButton shininessButton = new JButton(targetText(shininessTarget[0], t));
        shininessButton.addActionListener(e -> {
            ReplacerSettings.Source s = showRandomTargetDialog(dialog, t, shininessLabel, shininessTarget[0],
                    replacementMaterials);
            if (s != null) {
                shininessTarget[0] = s;
                shininessButton.setText(targetText(s, t));
                dialog.pack();
            }
        });
        Runnable updateShininess = () -> shininessButton.setEnabled(checks[shininessRow].isSelected());
        checks[shininessRow].addActionListener(e -> updateShininess.run());
        updateShininess.run();
        shininessButton.setVisible(hasReplacementMaterials);
        c.gridy = shininessRow + 1;
        c.gridx = 5;
        grid.add(shininessButton, c);

        final ReplacerSettings.RandomLook[] look = {current.look.copy()};
        final boolean[] defaultsHaveColors = {true};
        Runnable syncLook = () -> {
            if (defaultLookChoices != null) {
                List<Object> defaults = defaultLookChoices.apply(look[0].target);
                look[0].ensureDefaultChoices(defaults);

                defaultsHaveColors[0] = defaults.isEmpty() ? containsColor(look[0].choices) : containsColor(defaults);
            }
            boolean hasAnyColor = defaultsHaveColors[0] || (look[0].fromList && containsColor(look[0].choices));
            if (!hasAnyColor) {
                look[0].vary = false;
                look[0].fromList = true;
            } else if (!defaultsHaveColors[0]) {
                for (int cIdx = 0; cIdx < 3; cIdx++) {
                    if (look[0].componentModes[cIdx] == ReplacerSettings.RandomRanges.MODE_PERCENT) {
                        look[0].componentModes[cIdx] = ReplacerSettings.RandomRanges.MODE_VALUE;
                        look[0].componentMin[cIdx] = 0;
                        look[0].componentMax[cIdx] = 255;
                    }
                }
            }
        };
        syncLook.run();
        JCheckBox lookCheck = new JCheckBox(t.apply("color_texture", "Color/texture").trim(), current.look.enabled);
        JButton lookButton = new JButton(t.apply("randomLookConfigure", "Configure..."));
        JLabel lookSummary = new JLabel(lookText(look[0], t));
        JButton lookTargetButton = new JButton(targetText(look[0].target, t));
        String lookLabel = t.apply("color_texture", "Color/texture").trim();
        lookButton.addActionListener(e -> {
            syncLook.run();
            ReplacerSettings.RandomLook l = showRandomLookDialog(dialog, t, look[0], preferences, defaultsHaveColors[0]);
            if (l != null) {
                look[0] = l;
                syncLook.run();
                lookSummary.setText(lookText(look[0], t));
                dialog.pack();
            }
        });
        lookTargetButton.addActionListener(e -> {
            ReplacerSettings.Source s = showRandomTargetDialog(dialog, t, lookLabel, look[0].target,
                    replacementMaterials);
            if (s != null) {
                look[0].target = s;
                syncLook.run();
                lookTargetButton.setText(targetText(s, t));
                lookSummary.setText(lookText(look[0], t));
                dialog.pack();
            }
        });
        Runnable updateLook = () -> {
            boolean on = lookCheck.isSelected();
            lookButton.setEnabled(on);
            lookSummary.setEnabled(on);
            lookTargetButton.setEnabled(on);
        };
        lookCheck.addActionListener(e -> updateLook.run());
        updateLook.run();
        c.gridy = n + 1;
        c.gridx = 0;
        grid.add(lookCheck, c);
        c.gridx = 1;
        grid.add(lookButton, c);
        c.gridx = 2;
        c.gridwidth = 3;
        grid.add(lookSummary, c);
        c.gridwidth = 1;
        c.gridx = 5;
        lookTargetButton.setVisible(hasReplacementMaterials);
        grid.add(lookTargetButton, c);

        JCheckBox mirror = new JCheckBox(t.apply("randomMirror", "Mirror, probability:"), current.mirror);
        JSpinner probability = spinner(new SpinnerNumberModel(current.mirrorProbability, 0.0, 100.0, 5.0), 4);
        JLabel percent = new JLabel("%");
        JPanel mirrorRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        mirrorRow.add(mirror);
        mirrorRow.add(probability);
        mirrorRow.add(percent);
        Runnable updateMirror = () -> {
            probability.setEnabled(mirror.isSelected());
            percent.setEnabled(mirror.isSelected());
        };
        mirror.addActionListener(e -> updateMirror.run());
        updateMirror.run();

        Runnable updatePick = () -> {
            pieceScroll.setVisible(pick.isSelected());
            stopEditing(pieceTable);
            for (Runnable rotationRow : rotationRows) {
                rotationRow.run();
            }
            relayout.run();
        };
        pick.addActionListener(e -> updatePick.run());
        updatePick.run();

        JPanel top = new Rows()
                .add(baseHint(t))
                .add(pickPanel, 8, 0, false).panel;
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(new EmptyBorder(12, 12, 6, 12));
        panel.add(top, BorderLayout.NORTH);
        panel.add(grid, BorderLayout.CENTER);
        JPanel bottom = new Rows()
                .add(keepProportionsRow)
                .add(mirrorRow, 2, 0, false).panel;
        panel.add(bottom, BorderLayout.SOUTH);

        return show(dialog, panel, t, () -> {
            stopEditing(pieceTable);
            ReplacerSettings.RandomRanges r = new ReplacerSettings.RandomRanges();
            r.pickOnePiece = pick.isSelected();
            r.pieceWeights = pieceWeights;
            for (int i = 0; i < n; i++) {
                commit(mins[i]);
                commit(maxs[i]);
                r.enabled[i] = checks[i].isSelected();
                r.modes[i] = modes[i].getSelectedIndex();
                double a = ((Number) mins[i].getValue()).doubleValue();
                double b = ((Number) maxs[i].getValue()).doubleValue();
                r.min[i] = Math.min(a, b);
                r.max[i] = Math.max(a, b);
            }
            r.keepProportions = keepProportions.isSelected();
            commit(probability);
            r.mirror = mirror.isSelected();
            r.mirrorProbability = ((Number) probability.getValue()).doubleValue();
            r.shininessTarget = shininessTarget[0];
            r.look = look[0];
            r.look.enabled = lookCheck.isSelected();
            return r;
        });
    }

    private static String targetText(ReplacerSettings.Source target, BinaryOperator<String> t) {
        return (target.whole || target.materials.isEmpty()
                ? t.apply("randomWholeShort", "Whole replacement")
                : t.apply("randomMaterialsShort", "{0} materials").replace("{0}", String.valueOf(target.materials.size())))
                + "…";
    }

    private static String lookText(ReplacerSettings.RandomLook look, BinaryOperator<String> t) {
        List<String> parts = new ArrayList<>();
        if (look.fromList) {
            parts.add(t.apply("randomListCount", "{0} colors and textures").replace("{0}", String.valueOf(look.choices.size())));
        }
        if (look.vary) {
            parts.add(t.apply("randomComponentsShort", "Red, green and blue vary"));
        }
        return parts.isEmpty() ? "\u2013" : String.join(" + ", parts);
    }

    /** Displays the target material selector for randomized shininess or appearance. */
    static ReplacerSettings.Source showRandomTargetDialog(Window owner, BinaryOperator<String> t, String propertyLabel,
            ReplacerSettings.Source current, List<MaterialUtils.PieceMaterials> replacementMaterials) {
        JDialog dialog = newDialog(owner, t.apply("randomTargetTitle", "Random {0}: materials")
                .replace("{0}", propertyLabel.toLowerCase()));
        SourceChooser chooser = new SourceChooser(t, replacementMaterials, MaterialUtils.APPEARANCE, 240, false,
                t.apply("randomWholeModel", "Entire replacement (single value)"),
                null,
                t.apply("randomTheseMaterials", "Specific replacement materials (one value each):"),
                null,
                t.apply("noReplacementMaterials", "Select a replacement to list its materials."));
        chooser.load(current);
        chooser.onLayout = packer(dialog);
        JPanel panel = Rows.dialog()
                .add(bold(t.apply("randomApplyTo", "Apply to:")))
                .add(chooser.panel, 0, 0, true)
                .add(baseHint(t), 8, 0, false).panel;
        return show(dialog, panel, t, () -> {
            chooser.tables.stopEditing();
            return chooser.save();
        });
    }

    private static JLabel baseHint(BinaryOperator<String> t) {
        return hint(wrap(t.apply("randomBaseHint",
                "Base values use the selected properties to copy from the objects to replace, and the replacement's properties for the unselected ones."),
                460));
    }

    /** Displays the dialog for configuring candidate colors/textures and RGB channel variation. */
    static ReplacerSettings.RandomLook showRandomLookDialog(Window owner, BinaryOperator<String> t,
            ReplacerSettings.RandomLook current, UserPreferences preferences, boolean defaultsHaveColors) {
        JDialog dialog = newDialog(owner, t.apply("randomLookTitle", "Random color/texture"));
        Runnable relayout = packer(dialog);
        JCheckBox fromList = new JCheckBox(t.apply("randomFromList", "Pick from list:"), current.fromList);
        JCheckBox components = new JCheckBox(t.apply("randomVaryComponents", "Vary RGB channels:"), current.vary);
        JPanel fromListRow = withInfo(fromList, t.apply("randomListHint",
                "Double weight means twice as likely. Checked «Vary» applies RGB variation to that color."));
        JPanel componentsRow = withInfo(components, t.apply("randomVaryHint",
                "Applies to the list color or current color (0-255). Textured materials keep their texture."));

        String[] allModeNames = randomModeNames(t);
        String[] modeNames = defaultsHaveColors ? allModeNames : Arrays.copyOf(allModeNames, 2);
        String[] componentNames = {t.apply("randomRed", "Red"), t.apply("randomGreen", "Green"),
            t.apply("randomBlue", "Blue")};
        @SuppressWarnings({"unchecked", "rawtypes"})
        JComboBox<String>[] modes = new JComboBox[3];
        JSpinner[] mins = new JSpinner[3];
        JSpinner[] maxs = new JSpinner[3];
        JLabel[] labels = new JLabel[3];
        JLabel[] units = new JLabel[3];
        JCheckBox same = new JCheckBox(t.apply("randomSameComponents",
                "Equal variation across all channels (brightness only)"),
                current.sameComponents);
        JPanel componentsPanel = new JPanel(new GridBagLayout());
        GridBagConstraints c = new GridBagConstraints();
        c.anchor = GridBagConstraints.LINE_START;
        c.insets = new Insets(2, 0, 2, 8);
        Runnable updateComponents = () -> {
            for (int i = 0; i < 3; i++) {
                boolean on = i == 0 || !same.isSelected();
                labels[i].setEnabled(on);
                modes[i].setEnabled(on);
                mins[i].setEnabled(on);
                maxs[i].setEnabled(on);
                units[i].setEnabled(on);
                units[i].setText(modes[i].getSelectedIndex() == ReplacerSettings.RandomRanges.MODE_PERCENT
                        ? "%" : "(0-255)");
            }
            relayout.run();
        };
        for (int i = 0; i < 3; i++) {
            labels[i] = new JLabel(componentNames[i]);
            modes[i] = new JComboBox<>(modeNames);
            int initMode = current.componentModes[i] < modeNames.length ? current.componentModes[i] : 0;
            modes[i].setSelectedIndex(initMode);
            mins[i] = spinner(new SpinnerNumberModel(current.componentMin[i], -1000.0, 1000.0, 1.0), 5);
            maxs[i] = spinner(new SpinnerNumberModel(current.componentMax[i], -1000.0, 1000.0, 1.0), 5);
            units[i] = new JLabel();
            modes[i].addActionListener(e -> updateComponents.run());
            addGridRow(componentsPanel, c, i, labels[i], modes[i], mins[i], maxs[i], units[i]);
        }
        same.addActionListener(e -> updateComponents.run());
        c.gridy = 3;
        c.gridx = 0;
        c.gridwidth = 5;
        componentsPanel.add(same, c);

        final List<Object> choices = new ArrayList<>(current.choices);
        final List<Double> weights = new ArrayList<>(current.weights);
        final List<Boolean> varied = new ArrayList<>(current.varied);
        ChoiceTableModel choiceModel = new ChoiceTableModel(choices, weights, varied, new String[]{
            t.apply("color_texture", "Color/texture"), t.apply("materialValue", "Value"),
            t.apply("randomWeight", "Weight"), t.apply("randomProbability", "Probability"),
            t.apply("randomVaryColumn", "Vary")});
        choiceModel.varyEnabled = current.vary;
        JTable choiceTable = new JTable(choiceModel);
        choiceTable.setRowHeight(26);
        choiceTable.getTableHeader().setReorderingAllowed(false);
        DefaultTableCellRenderer centered = new DefaultTableCellRenderer();
        centered.setHorizontalAlignment(SwingConstants.CENTER);
        DefaultTableCellRenderer swatchRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value, boolean isSelected,
                    boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(table, "", isSelected, hasFocus, row, column);
                cellColors(this, table, isSelected, false);
                setIcon(value instanceof Icon ? (Icon) value : null);
                setHorizontalAlignment(SwingConstants.CENTER);
                return this;
            }
        };
        final TableCellRenderer checkRenderer = choiceTable.getDefaultRenderer(Boolean.class);
        final DefaultTableCellRenderer noCheck = new DefaultTableCellRenderer();
        noCheck.setHorizontalAlignment(SwingConstants.CENTER);
        TableCellRenderer varyRenderer = (table, value, isSelected, hasFocus, row, column) -> {
            if (!(choices.get(row) instanceof Integer)) {
                noCheck.getTableCellRendererComponent(table, "\u2013", isSelected, hasFocus, row, column);
                cellColors(noCheck, table, isSelected, false);
                return noCheck;
            }
            return checkRenderer.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
        };
        Runnable configureChoiceColumns = () -> {
            int[] widths = {90, 220, 70, 90, 60};
            for (int column = 0; column < choiceModel.getColumnCount(); column++) {
                choiceTable.getColumnModel().getColumn(column).setPreferredWidth(widths[column]);
            }
            choiceTable.getColumnModel().getColumn(0).setCellRenderer(swatchRenderer);
            choiceTable.getColumnModel().getColumn(3).setCellRenderer(centered);
            if (choiceModel.getColumnCount() > ChoiceTableModel.VARY) {
                choiceTable.getColumnModel().getColumn(ChoiceTableModel.VARY).setCellRenderer(varyRenderer);
            }
        };
        configureChoiceColumns.run();

        final boolean[] choicesEdited = {current.customChoices};
        JLabel count = new JLabel();
        Runnable updateCount = () -> count.setText(t.apply("randomListCount", "{0} items")
                .replace("{0}", String.valueOf(choices.size())));

        JButton addColor = new JButton(t.apply("randomAddColor", "Add color..."));
        addColor.addActionListener(e -> {
            Color color = javax.swing.JColorChooser.showDialog(dialog, t.apply("randomAddColor", "Add color...")
                    .replace("...", "").replace("…", ""), Color.WHITE);
            if (color != null) {
                choiceModel.add(color.getRGB());
            }
        });
        JButton remove = new JButton(t.apply("randomRemove", "Remove"));
        remove.addActionListener(e -> {
            stopEditing(choiceTable);
            int[] rows = choiceTable.getSelectedRows();
            for (int i = rows.length - 1; i >= 0; i--) {
                choiceModel.remove(rows[i]);
            }
        });
        Runnable updateRemove = () -> remove.setEnabled(choiceTable.getSelectedRowCount() > 0);
        choiceTable.getSelectionModel().addListSelectionListener(e -> updateRemove.run());
        updateRemove.run();
        JPanel listButtons = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        listButtons.add(addColor);
        JComponent textureButton = textureChooser(t, preferences, choiceModel::add);
        if (textureButton != null) {
            listButtons.add(new JLabel(t.apply("randomAddTexture", "Add texture:")));
            listButtons.add(textureButton);
        }
        listButtons.add(remove);

        JScrollPane choiceScroll = new JScrollPane(choiceTable);
        choiceScroll.setPreferredSize(new Dimension(470, 170));
        JPanel listPanel = new Rows()
                .add(choiceScroll, 0, 0, true)
                .add(listButtons, 4, 0, false)
                .add(count, 4, 0, false).panel;

        JPanel panel = Rows.dialog()
                .add(bold(t.apply("randomDraw", "Mode:")))
                .add(fromListRow)
                .add(listPanel, 2, 26, true)
                .add(componentsRow, 8, 0, false)
                .add(componentsPanel, 2, 26, false)
                .add(baseHint(t), 10, 0, false).panel;

        Runnable update = () -> {
            boolean listHasColors = containsColor(choices);
            boolean hasColors = defaultsHaveColors || (fromList.isSelected() && listHasColors);
            if (!defaultsHaveColors && !listHasColors && !fromList.isSelected()) {
                fromList.setSelected(true);
            }
            if (!hasColors) {
                components.setSelected(false);
            }
            componentsRow.setVisible(hasColors);
            boolean compOn = hasColors && components.isSelected();
            componentsPanel.setVisible(compOn);
            listPanel.setVisible(fromList.isSelected());
            stopEditing(choiceTable);
            boolean showVaryCol = compOn && listHasColors;
            if (choiceModel.varyEnabled != showVaryCol) {
                choiceModel.varyEnabled = showVaryCol;
                choiceModel.fireTableStructureChanged();
                configureChoiceColumns.run();
            }
            choiceTable.repaint();
            updateComponents.run();
        };
        choiceModel.addTableModelListener(e -> {
            if (e.getColumn() != javax.swing.event.TableModelEvent.ALL_COLUMNS || e.getFirstRow() != javax.swing.event.TableModelEvent.HEADER_ROW) {
                choicesEdited[0] = true;
                updateCount.run();
                SwingUtilities.invokeLater(update);
            }
        });
        updateCount.run();
        components.addActionListener(e -> update.run());
        fromList.addActionListener(e -> update.run());
        update.run();

        return show(dialog, panel, t, () -> {
            stopEditing(choiceTable);
            ReplacerSettings.RandomLook look = new ReplacerSettings.RandomLook();
            look.enabled = current.enabled;
            look.target = ReplacerSettings.copyOf(current.target);
            look.fromList = fromList.isSelected();
            look.vary = components.isSelected();
            look.sameComponents = same.isSelected();
            for (int i = 0; i < 3; i++) {
                commit(mins[i]);
                commit(maxs[i]);
                look.componentModes[i] = modes[i].getSelectedIndex();
                double a = ((Number) mins[i].getValue()).doubleValue();
                double b = ((Number) maxs[i].getValue()).doubleValue();
                look.componentMin[i] = Math.min(a, b);
                look.componentMax[i] = Math.max(a, b);
            }
            look.choices.addAll(choices);
            look.weights.addAll(weights);
            look.varied.addAll(varied);
            look.customChoices = choicesEdited[0] && !choices.isEmpty();
            return look;
        });
    }

    private static JComponent textureChooser(BinaryOperator<String> t, UserPreferences preferences,
            java.util.function.Consumer<Object> added) {
        if (preferences == null) {
            return null;
        }
        try {
            final TextureChoiceController controller = new TextureChoiceController(
                    t.apply("randomAddTexture", "Add texture:").replace(":", "").replace("\uff1a", "").trim(), preferences,
                    new SwingViewFactory(), new FileContentManager(preferences));
            controller.addPropertyChangeListener(TextureChoiceController.Property.TEXTURE, e -> {
                HomeTexture texture = controller.getTexture();
                if (texture != null) {
                    added.accept(texture);
                    controller.setTexture(null);
                }
            });
            return (JComponent) controller.getView();
        } catch (RuntimeException | LinkageError ex) {
            return null;
        }
    }

    private static final class PieceTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        private final List<HomePieceOfFurniture> objects;
        private final List<Double> weights;
        private final String[] headers;

        PieceTableModel(List<HomePieceOfFurniture> objects, List<Double> weights, String[] headers) {
            this.objects = objects;
            this.weights = weights;
            this.headers = headers;
            while (weights.size() < objects.size()) {
                weights.add(1.0);
            }
        }

        @Override public int getRowCount() { return objects.size(); }

        @Override public int getColumnCount() { return headers.length; }

        @Override public String getColumnName(int column) { return headers[column]; }

        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Icon.class : column == 2 ? Double.class : String.class; }

        @Override public boolean isCellEditable(int row, int column) { return column == 2; }

        @Override
        public Object getValueAt(int row, int column) {
            HomePieceOfFurniture object = objects.get(row);
            return column == 0 ? (object.getIcon() != null ? contentIcon(object.getIcon(), 24) : null)
                    : column == 1 ? object.getName()
                    : column == 2 ? weights.get(row)
                    : probabilityText(weights, row);
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == 2 && value instanceof Number) {
                weights.set(row, Math.max(0, ((Number) value).doubleValue()));
                fireTableRowsUpdated(0, objects.size() - 1);
            }
        }
    }

    private static final class ChoiceTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        static final int WEIGHT = 2;
        static final int VARY = 4;
        private final List<Object> choices;
        private final List<Double> weights;
        private final List<Boolean> varied;
        private final String[] headers;

        boolean varyEnabled = true;

        ChoiceTableModel(List<Object> choices, List<Double> weights, List<Boolean> varied, String[] headers) {
            this.choices = choices;
            this.weights = weights;
            this.varied = varied;
            this.headers = headers;
            while (weights.size() < choices.size()) {
                weights.add(1.0);
            }
            while (varied.size() < choices.size()) {
                varied.add(Boolean.TRUE);
            }
        }

        void add(Object choice) {
            choices.add(choice);
            weights.add(1.0);
            varied.add(Boolean.TRUE);
            fireTableDataChanged();
        }

        void remove(int row) {
            choices.remove(row);
            weights.remove(row);
            varied.remove(row);
            fireTableDataChanged();
        }

        @Override public int getRowCount() { return choices.size(); }

        @Override public int getColumnCount() { return varyEnabled ? headers.length : headers.length - 1; }

        @Override public String getColumnName(int column) { return headers[column]; }

        @Override public Class<?> getColumnClass(int column) { return column == 0 ? Icon.class : column == WEIGHT ? Double.class : column == VARY ? Boolean.class : String.class; }

        @Override public boolean isCellEditable(int row, int column) { return column == WEIGHT || column == VARY && varyEnabled && choices.get(row) instanceof Integer; }

        @Override
        public Object getValueAt(int row, int column) {
            Object choice = choices.get(row);
            return column == 0 ? swatch(MaterialUtils.newMaterial("", null,
                            choice instanceof Integer ? (Integer) choice : null,
                            choice instanceof HomeTexture ? (HomeTexture) choice : null, null))
                    : column == 1 ? MaterialUtils.formatPropertyValue(MaterialUtils.APPEARANCE, choice)
                    : column == WEIGHT ? weights.get(row)
                    : column == VARY ? varied.get(row)
                    : probabilityText(weights, row);
        }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (column == WEIGHT && value instanceof Number) {
                weights.set(row, Math.max(0, ((Number) value).doubleValue()));

                fireTableRowsUpdated(0, choices.size() - 1);
            } else if (column == VARY && value instanceof Boolean) {
                varied.set(row, (Boolean) value);
                fireTableRowsUpdated(row, row);
            }
        }
    }

    /** Displays the dialog for configuring subgroup replacement depth. */
    static ReplacerSettings.GroupLevels showGroupLevelsDialog(Window owner, BinaryOperator<String> t,
            ReplacerSettings.GroupLevels current) {
        JDialog dialog = newDialog(owner, t.apply("InternalGroups", "Subgroup depth"));
        Runnable relayout = packer(dialog);
        JRadioButton all = new JRadioButton(t.apply("allinternalgroups", "All levels"), current.levels <= 0);
        JRadioButton upTo = new JRadioButton(t.apply("selectstopgroup", "Up to level:"), current.levels > 0);
        radioGroup(all, upTo);
        JSpinner levels = spinner(new SpinnerNumberModel(Math.max(1, current.levels), 1, 999, 1), 3);
        JRadioButton fromTop = new JRadioButton(t.apply("countFromOuter", "From outer group"), !current.fromBottom);
        JRadioButton fromBottom = new JRadioButton(t.apply("countFromDeepest", "From innermost subgroup"), current.fromBottom);
        radioGroup(fromTop, fromBottom);
        JCheckBox whole = new JCheckBox(t.apply("limitGroupsWhole",
                "Replace subgroups at the limit level as single units"), current.wholeAtLimit);

        JPanel limitRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
        limitRow.add(all);
        limitRow.add(upTo);
        limitRow.add(levels);
        JPanel fromTopRow = withInfo(fromTop, t.apply("depthHintTop",
                "1 = elements directly in the group; 2 = also those in its subgroups; etc."));
        JPanel fromBottomRow = withInfo(fromBottom, t.apply("depthHintBottom",
                "1 = all elements down to the deepest level; 2 = all except the deepest subgroups; etc."));
        Rows rows = Rows.dialog().add(limitRow);
        JComponent[] dependent = {fromTopRow, fromBottomRow, whole};
        for (JComponent component : dependent) {
            rows.add(component, 2, 26, false);
        }
        JPanel panel = rows.panel;
        Runnable update = () -> {
            boolean sub = upTo.isSelected();
            levels.setVisible(sub);
            for (JComponent component : dependent) {
                component.setVisible(sub);
            }
            relayout.run();
        };
        all.addActionListener(e -> update.run());
        upTo.addActionListener(e -> update.run());
        update.run();

        return show(dialog, panel, t, () -> {
            commit(levels);
            ReplacerSettings.GroupLevels r = new ReplacerSettings.GroupLevels();
            r.levels = upTo.isSelected() ? ((Number) levels.getValue()).intValue() : 0;
            r.fromBottom = fromBottom.isSelected();
            r.wholeAtLimit = whole.isSelected();
            return r;
        });
    }

    /** Displays the dialog for configuring proportional scaling of unchecked dimensions. */
    static ReplacerSettings.ProportionalSizes showProportionalDialog(Window owner, BinaryOperator<String> t,
            ReplacerSettings.ProportionalSizes current, JCheckBox[] sizes) {
        JDialog dialog = newDialog(owner, t.apply("proportionalTitle", "Proportional dimensions"));
        Runnable relayout = packer(dialog);
        JCheckBox enabled = new JCheckBox(t.apply("proportionalSizes", "Scale unchecked dimensions proportionally"), current.enabled);

        List<Integer> bases = new ArrayList<>();
        List<JRadioButton> radios = new ArrayList<>();
        ButtonGroup group = new ButtonGroup();
        bases.add(-1);
        radios.add(new JRadioButton(t.apply("proportionalAverage", "Average of both")));
        for (int i = 0; i < sizes.length; i++) {
            if (sizes[i].isSelected()) {
                bases.add(i);
                radios.add(new JRadioButton(t.apply("proportionalTo", "Follow {0}").replace("{0}", sizes[i].getText())));
            }
        }
        boolean two = bases.size() == 3;
        int chosen = Math.max(0, bases.indexOf(current.base));
        for (int i = 0; i < radios.size(); i++) {
            group.add(radios.get(i));
            radios.get(i).setSelected(i == chosen);
        }

        Rows rows = Rows.dialog()
                .add(withInfo(enabled, t.apply("proportionalSizesTip",
                        "Unchecked dimensions scale by the same ratio as checked ones.")));
        if (two) {
            JLabel baseLabel = new JLabel(t.apply("proportionalBaseTip", "Reference ratio:"));
            rows.add(baseLabel, 8, 26, false);
            for (int i = 0; i < radios.size(); i++) {
                rows.add(radios.get(i), i == 0 ? 2 : 0, 38, false);
            }
            Runnable update = () -> {
                boolean on = enabled.isSelected();
                baseLabel.setVisible(on);
                for (JRadioButton radio : radios) {
                    radio.setVisible(on);
                }
                relayout.run();
            };
            enabled.addActionListener(e -> update.run());
            update.run();
        }
        JPanel panel = rows.panel;

        return show(dialog, panel, t, () -> {
            ReplacerSettings.ProportionalSizes r = new ReplacerSettings.ProportionalSizes();
            r.enabled = enabled.isSelected();
            r.base = current.base;
            for (int i = 0; two && i < radios.size(); i++) {
                if (radios.get(i).isSelected()) {
                    r.base = bases.get(i);
                }
            }
            return r;
        });
    }

    /** Displays the 3x3 anchor grid dialog for aligning replacements in the 2D plan. */
    static ReplacerSettings.Alignment showAlignmentDialog(Window owner, BinaryOperator<String> t,
            ReplacerSettings.Alignment current) {
        JDialog dialog = newDialog(owner, t.apply("alignTitle", "Alignment"));
        String[][] keys = {
            {"alignBackLeft", "Back left"}, {"alignBack", "Back"}, {"alignBackRight", "Back right"},
            {"alignLeft", "Left"}, {"alignCenter", "Center"}, {"alignRight", "Right"},
            {"alignFrontLeft", "Front left"}, {"alignFront", "Front"}, {"alignFrontRight", "Front right"}};

        JPanel grid = new JPanel(new GridLayout(3, 3, 6, 4));
        grid.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(TABLE_BORDER),
                new EmptyBorder(6, 8, 6, 8)));
        ButtonGroup group = new ButtonGroup();
        JRadioButton[] radios = new JRadioButton[9];
        for (int i = 0; i < 9; i++) {
            radios[i] = new JRadioButton(t.apply(keys[i][0], keys[i][1]));
            radios[i].setSelected(i == (current.row + 1) * 3 + current.column + 1);
            group.add(radios[i]);
            grid.add(radios[i]);
        }
        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(new EmptyBorder(12, 12, 6, 12));
        panel.add(withInfo(new JLabel(t.apply("alignHint", "Anchor point when dimensions differ (top view):")),
                t.apply("alignDoorHint", "Wall-bound doors and windows remain aligned to their wall opening.")),
                BorderLayout.NORTH);
        panel.add(grid, BorderLayout.CENTER);

        return show(dialog, panel, t, () -> {
            ReplacerSettings.Alignment r = new ReplacerSettings.Alignment();
            for (int i = 0; i < 9; i++) {
                if (radios[i].isSelected()) {
                    r.column = i % 3 - 1;
                    r.row = i / 3 - 1;
                }
            }
            return r;
        });
    }

    private static String unit(String property, int mode) {
        if (mode == ReplacerSettings.RandomRanges.MODE_PERCENT) {
            return "%";
        }
        switch (property) {
            case "angle":
            case "pitch":
            case "roll":
                return "°";
            case "light_power":
            case "shininess":
                return "%";
            default:
                return "cm";
        }
    }

    static void commit(JSpinner spinner) {
        try {
            spinner.commitEdit();
        } catch (java.text.ParseException ex) {
        }
    }

    private static final int COL_CHECK = 0;
    private static final int COL_HIGHLIGHT = 1;
    private static final int COL_PART = 2;
    private static final int COL_SWATCH = 3;
    private static final int COL_NAME = 4;
    private static final int COL_VALUE = 5;

    private static final class MultiPieceTableGroup {
        final JPanel containerPanel = new JPanel(new GridBagLayout());
        final List<CheckTableModel> models = new ArrayList<>();
        final List<JTable> tables = new ArrayList<>();

        MultiPieceTableGroup(List<MaterialUtils.PieceMaterials> pieceList, int property, BinaryOperator<String> t,
                boolean dimUnchecked, boolean single) {
            containerPanel.setBackground(UIManager.getColor("Table.background"));
            GridBagConstraints gc = new GridBagConstraints();
            gc.gridx = 0;
            gc.gridy = 0;
            gc.weightx = 1;
            gc.fill = GridBagConstraints.HORIZONTAL;
            gc.anchor = GridBagConstraints.NORTHWEST;
            gc.insets = new Insets(2, 4, 6, 4);

            String[] headers = new String[6];
            headers[COL_CHECK] = "";
            headers[COL_HIGHLIGHT] = t.apply("materialPart", "Part");
            headers[COL_PART] = t.apply("materialLook", "Look");
            headers[COL_SWATCH] = t.apply("color_texture", "Color/texture");
            headers[COL_NAME] = t.apply("materialName", "Material");
            headers[COL_VALUE] = t.apply("materialValue", "Value");
            List<Integer> kinds = new ArrayList<>(Arrays.asList(COL_CHECK, COL_HIGHLIGHT, COL_PART));
            if (property == MaterialUtils.APPEARANCE) {
                kinds.add(COL_SWATCH);
            }
            kinds.add(COL_NAME);
            kinds.add(COL_VALUE);

            for (MaterialUtils.PieceMaterials pm : pieceList) {
                String pieceTitle = pm.piece.getName() != null ? pm.piece.getName() : "";
                JLabel header = new JLabel(pieceTitle, contentIcon(pm.piece.getIcon(), 28), SwingConstants.LEADING);
                header.setFont(header.getFont().deriveFont(Font.BOLD));
                header.setBorder(new EmptyBorder(4, 2, 3, 2));

                int n = pm.entries.size();
                Icon[] highlighted = new Icon[n];
                Icon[] parts = new Icon[n];
                Icon[] swatches = new Icon[n];
                String[] names = new String[n];
                String[] values = new String[n];
                for (int i = 0; i < n; i++) {
                    MaterialUtils.MaterialEntry entry = pm.entries.get(i);
                    swatches[i] = swatches(entry);
                    names[i] = entry.isWholePiece() ? t.apply("wholeObject", "Whole object") : entry.rawName;
                    values[i] = variantsText(entry.variantTexts);
                }
                CheckTableModel model = new CheckTableModel(pm.entries, kinds, headers,
                        highlighted, parts, swatches, names, values, dimUnchecked);
                if (single) {
                    model.group = java.util.Collections.singletonList(model);
                }
                final MaterialUtils.PieceMaterials pieceMaterials = pm;
                PHOTOS.submit(() -> {
                    for (int i = 0; i < pieceMaterials.entries.size(); i++) {
                        MaterialUtils.MaterialEntry entry = pieceMaterials.entries.get(i);
                        final Icon highlight = icon(MaterialPreview.highlighted(pieceMaterials.piece, entry.rawName, PHOTO));
                        final Icon part = icon(MaterialPreview.part(pieceMaterials.piece, entry.rawName, entry.material, PHOTO));
                        final int row = i;
                        SwingUtilities.invokeLater(() -> model.setPictures(row, highlight, part));
                    }
                });
                JTable table = materialTable(model);
                models.add(model);
                tables.add(table);

                JPanel tableBox = new JPanel(new BorderLayout());
                tableBox.setBorder(BorderFactory.createLineBorder(TABLE_BORDER));
                tableBox.add(table.getTableHeader(), BorderLayout.NORTH);
                tableBox.add(table, BorderLayout.CENTER);

                JPanel pieceCard = new JPanel(new BorderLayout(0, 2));
                pieceCard.setOpaque(false);
                pieceCard.add(header, BorderLayout.NORTH);
                pieceCard.add(tableBox, BorderLayout.CENTER);

                containerPanel.add(pieceCard, gc);
                gc.gridy++;
            }

            gc.weighty = 1;
            gc.fill = GridBagConstraints.BOTH;
            JPanel filler = new JPanel();
            filler.setOpaque(false);
            containerPanel.add(filler, gc);
        }

        void stopEditing() {
            for (JTable table : tables) {
                ReplacerDialogs.stopEditing(table);
            }
        }

        void repaint() {
            for (JTable table : tables) {
                table.repaint();
            }
        }

        void check(List<String> names) {
            for (CheckTableModel model : models) {
                boolean found = false;
                for (int i = 0; i < model.entries.size(); i++) {
                    MaterialUtils.MaterialEntry entry = model.entries.get(i);
                    model.checked[i] = (model.group == null || !found)
                            && (names.contains(entry.key) || names.contains(entry.rawName));
                    found |= model.checked[i];
                }
                model.fireTableDataChanged();
            }
        }

        List<String> checkedKeys() {
            List<String> keys = new ArrayList<>();
            for (CheckTableModel model : models) {
                for (int i = 0; i < model.entries.size(); i++) {
                    if (model.checked[i]) {
                        keys.add(model.entries.get(i).key);
                    }
                }
            }
            return keys;
        }

        void onCheckChange(Runnable listener) {
            for (CheckTableModel model : models) {
                model.addTableModelListener(e -> {
                    if (!model.updatingPictures) {
                        listener.run();
                    }
                });
            }
        }

        void onSelect(BiConsumer<CheckTableModel, Integer> listener) {
            for (int i = 0; i < tables.size(); i++) {
                final JTable table = tables.get(i);
                final CheckTableModel model = models.get(i);
                table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
                table.getSelectionModel().addListSelectionListener(e -> {
                    int row = table.getSelectedRow();
                    if (!e.getValueIsAdjusting() && row >= 0) {
                        for (JTable other : tables) {
                            if (other != table) {
                                other.clearSelection();
                            }
                        }
                        listener.accept(model, row);
                    }
                });
            }
        }

        void selectFirst() {
            for (JTable table : tables) {
                if (table.getRowCount() > 0) {
                    table.setRowSelectionInterval(0, 0);
                    return;
                }
            }
        }
    }

    private static String variantsText(List<String> texts) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < texts.size() && i < SHOWN_VARIANTS; i++) {
            text.append(i > 0 ? ", " : "").append(texts.get(i));
        }
        if (texts.size() > SHOWN_VARIANTS) {
            text.append("  +").append(texts.size() - SHOWN_VARIANTS);
        }
        return text.toString();
    }

    private static Icon swatches(MaterialUtils.MaterialEntry entry) {
        final List<Icon> icons = new ArrayList<>();
        for (int i = 0; i < entry.variants.size() && i < SHOWN_VARIANTS; i++) {
            if (entry.variants.get(i) != null) {
                icons.add(swatch(entry.variants.get(i)));
            }
        }
        if (icons.size() <= 1) {
            return icons.isEmpty() ? null : icons.get(0);
        }
        final String more = entry.variants.size() > SHOWN_VARIANTS ? "+" + (entry.variants.size() - SHOWN_VARIANTS) : "";
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                for (int i = 0; i < icons.size(); i++) {
                    icons.get(i).paintIcon(c, g, x + i * 26, y);
                }
                if (!more.isEmpty()) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    g2.setColor(c != null ? c.getForeground() : Color.DARK_GRAY);
                    if (c != null) {
                        g2.setFont(c.getFont());
                    }
                    g2.drawString(more, x + icons.size() * 26 + 2, y + 16);
                    g2.dispose();
                }
            }

            @Override public int getIconWidth() { return icons.size() * 26 + (more.isEmpty() ? 0 : 30); }

            @Override public int getIconHeight() { return 23; }
        };
    }

    private static Icon icon(BufferedImage image) {
        return image != null ? new ImageIcon(image) : null;
    }

    /** Loads, scales, and caches a Content image asynchronously off the EDT. */
    private static Icon contentIcon(final Content content, final int size) {
        if (content == null) {
            return null;
        }
        final List<Object> key = Arrays.<Object>asList(
                content instanceof URLContent ? ((URLContent) content).getURL().toString() : content, size);
        synchronized (ICONS) {
            if (ICONS.containsKey(key)) {
                return ICONS.get(key);
            }
        }
        Icon asyncIcon = new Icon() {
            private volatile Icon delegate;
            private volatile boolean requested;

            @Override public int getIconWidth() { return delegate != null ? delegate.getIconWidth() : size; }

            @Override public int getIconHeight() { return delegate != null ? delegate.getIconHeight() : size; }

            @Override
            public void paintIcon(final Component c, Graphics g, int x, int y) {
                Icon d = delegate;
                if (d != null) {
                    d.paintIcon(c, g, x, y);
                } else if (!requested) {
                    requested = true;
                    PHOTOS.submit(() -> {
                        Icon loaded = null;
                        try (InputStream in = content.openStream()) {
                            BufferedImage image = ImageIO.read(in);
                            if (image != null && image.getWidth() > 0 && image.getHeight() > 0) {
                                double scale = Math.min((double) size / image.getWidth(), (double) size / image.getHeight());
                                loaded = new ImageIcon(scaled(image, Math.max(1, (int) Math.round(image.getWidth() * scale)),
                                        Math.max(1, (int) Math.round(image.getHeight() * scale))));
                            }
                        } catch (IOException | RuntimeException ex) {
                        }
                        delegate = loaded;
                        synchronized (ICONS) {
                            ICONS.put(key, loaded != null ? loaded : this);
                        }
                        if (c != null) {
                            SwingUtilities.invokeLater(c::repaint);
                        }
                    });
                }
            }
        };
        synchronized (ICONS) {
            ICONS.put(key, asyncIcon);
        }
        return asyncIcon;
    }

    /** Progressive bilinear downscaling to prevent aliasing on small icons. */
    private static BufferedImage scaled(BufferedImage image, int width, int height) {
        BufferedImage current = image;
        int w = image.getWidth();
        int h = image.getHeight();
        do {
            w = Math.max(width, w / 2);
            h = Math.max(height, h / 2);
            if (w < width || h < height || w > current.getWidth() && h > current.getHeight()) {
                w = width;
                h = height;
            }
            BufferedImage next = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = next.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(current, 0, 0, w, h, null);
            g.dispose();
            current = next;
        } while (w != width || h != height);
        return current;
    }

    private static JDialog newDialog(Window owner, String title) {
        JDialog dialog = new JDialog(owner, title, java.awt.Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setAlwaysOnTop(owner != null && owner.isAlwaysOnTop());
        dialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        return dialog;
    }

    private static <T> T show(JDialog dialog, JPanel panel, BinaryOperator<String> t, Supplier<T> onOk) {
        final List<T> result = new ArrayList<>(1);
        JButton ok = new JButton(t.apply("accept", "OK"));
        ok.addActionListener(e -> {
            result.add(onOk.get());
            dialog.dispose();
        });
        JButton cancel = new JButton(t.apply("cancel", "Cancel"));
        cancel.addActionListener(e -> dialog.dispose());
        JPanel buttons = new JPanel(new FlowLayout(FlowLayout.TRAILING));
        buttons.add(cancel);
        buttons.add(ok);
        JPanel content = new JPanel(new BorderLayout());
        content.add(panel, BorderLayout.CENTER);
        content.add(buttons, BorderLayout.SOUTH);
        dialog.setContentPane(content);
        dialog.getRootPane().setDefaultButton(ok);
        dialog.getRootPane().registerKeyboardAction(e -> dialog.dispose(),
                KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0), JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.addWindowListener(new WindowAdapter() {
            @Override
            public void windowOpened(WindowEvent e) {
                content.revalidate();
                content.repaint();
            }
        });
        dialog.pack();
        dialog.setLocationRelativeTo(dialog.getOwner());
        dialog.setVisible(true);
        return result.isEmpty() ? null : result.get(0);
    }

    private static Runnable packer(JDialog dialog) {
        return () -> {
            if (dialog.isShowing()) {
                dialog.pack();
            }
        };
    }

    private static String[] randomModeNames(BinaryOperator<String> t) {
        return new String[]{
            t.apply("randomValue", "Value between"),
            t.apply("randomAdd", "Add between"),
            t.apply("randomPercent", "% of the value between")};
    }

    private static JSpinner spinner(SpinnerNumberModel model, int columns) {
        JSpinner spinner = new JSpinner(model);
        ((JSpinner.DefaultEditor) spinner.getEditor()).getTextField().setColumns(columns);
        return spinner;
    }

    private static void addGridRow(JPanel grid, GridBagConstraints c, int row, JComponent... cells) {
        c.gridy = row;
        for (int col = 0; col < cells.length; col++) {
            c.gridx = col;
            grid.add(cells[col], c);
        }
    }

    private static String probabilityText(List<Double> weights, int row) {
        double total = 0;
        for (double w : weights) {
            total += w;
        }
        return total > 0 ? String.format("%.1f %%", weights.get(row) * 100 / total) : "";
    }

    private static final class Rows {
        final JPanel panel = new JPanel(new GridBagLayout());
        private final GridBagConstraints c = new GridBagConstraints();

        Rows() {
            c.gridx = 0;
            c.gridy = 0;
            c.anchor = GridBagConstraints.LINE_START;
            c.weightx = 1;
        }

        static Rows dialog() {
            Rows rows = new Rows();
            rows.panel.setBorder(new EmptyBorder(12, 12, 6, 12));
            return rows;
        }

        Rows add(JComponent component) {
            return add(component, 0, 0, false);
        }

        Rows add(JComponent component, int top, int left, boolean grows) {
            c.insets = new Insets(top, left, 0, 0);
            c.fill = grows ? GridBagConstraints.BOTH : GridBagConstraints.HORIZONTAL;
            c.weighty = grows ? 1 : 0;
            panel.add(component, c);
            c.gridy++;
            return this;
        }
    }

    private static JLabel bold(String text) {
        JLabel label = new JLabel(text);
        label.setFont(label.getFont().deriveFont(Font.BOLD));
        return label;
    }

    static String wrap(String text, int width) {
        return "<html><div style='width:" + width + "px'>" + text + "</div></html>";
    }

    static JLabel hint(String text) {
        JLabel label = new JLabel(text);
        label.setForeground(HINT);
        label.setFont(label.getFont().deriveFont(11f));
        return label;
    }

    static JLabel info(String tip) {
        if (tip == null || tip.trim().isEmpty()) {
            return null;
        }
        javax.swing.ToolTipManager.sharedInstance().setInitialDelay(150);
        javax.swing.ToolTipManager.sharedInstance().setDismissDelay(12000);
        JLabel label = new JLabel("\u24D8");
        label.setForeground(new Color(47, 111, 222));
        label.setFont(label.getFont().deriveFont(Font.BOLD, 12f));
        label.setCursor(java.awt.Cursor.getPredefinedCursor(java.awt.Cursor.HAND_CURSOR));
        label.setToolTipText(wrap(tip.trim(), 280));
        return label;
    }

    static JPanel withInfo(JComponent component, String tip) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        panel.setOpaque(false);
        panel.add(component);
        JLabel icon = info(tip);
        if (icon != null) {
            icon.setBorder(new EmptyBorder(0, 4, 0, 0));
            panel.add(icon);
        }
        return panel;
    }

    static String joinTips(String first, String second) {
        if (first == null || first.isEmpty()) {
            return second;
        }
        if (second == null || second.isEmpty()) {
            return first;
        }
        return first + " " + second;
    }

    static boolean containsColor(List<?> choices) {
        if (choices != null) {
            for (Object item : choices) {
                if (item instanceof Integer) {
                    return true;
                }
            }
        }
        return false;
    }

    private static JScrollPane scroll(JComponent view, int height, int minWidth) {
        JScrollPane scroll = new JScrollPane(view);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        scroll.getViewport().setScrollMode(JViewport.SIMPLE_SCROLL_MODE);
        scroll.getVerticalScrollBar().addAdjustmentListener(e -> scroll.getViewport().repaint());
        scroll.getHorizontalScrollBar().addAdjustmentListener(e -> scroll.getViewport().repaint());
        scroll.setPreferredSize(new Dimension(Math.max(minWidth, view.getPreferredSize().width + 24), height));
        return scroll;
    }

    private static void stopEditing(JTable table) {
        if (table.isEditing()) {
            table.getCellEditor().stopCellEditing();
        }
    }

    private static JTable materialTable(final CheckTableModel model) {
        JTable table = new JTable(model);
        table.setRowHeight(PHOTO + 4);
        table.getTableHeader().setReorderingAllowed(false);
        int[] widths = {30, PHOTO + 10, PHOTO + 10, 46, 160, 170};
        for (int column = 0; column < model.getColumnCount(); column++) {
            int kind = model.kind(column);
            table.getColumnModel().getColumn(column).setPreferredWidth(widths[kind]);
            if (kind == COL_SWATCH) {
                int width = widths[kind];
                for (int row = 0; row < model.getRowCount(); row++) {
                    Object icon = model.getValueAt(row, column);
                    if (icon instanceof Icon) {
                        width = Math.max(width, ((Icon) icon).getIconWidth() + 14);
                    }
                }
                table.getColumnModel().getColumn(column).setPreferredWidth(width);
                table.getColumnModel().getColumn(column).setMaxWidth(width + 12);
            } else if (kind == COL_CHECK || kind == COL_HIGHLIGHT || kind == COL_PART) {
                table.getColumnModel().getColumn(column).setMaxWidth(widths[kind] + 12);
            }
        }

        DefaultTableCellRenderer iconRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                    boolean hasFocus, int row, int column) {
                Icon icon = value instanceof Icon ? (Icon) value : null;
                int kind = model.kind(column);
                String text = icon != null ? "" : (kind == COL_HIGHLIGHT || kind == COL_PART) && model.pending[row]
                        ? "\u2026" : "\u2013";
                super.getTableCellRendererComponent(t, text, isSelected, hasFocus, row, column);
                cellColors(this, t, isSelected, false);
                setIcon(icon);
                setHorizontalAlignment(SwingConstants.CENTER);
                return this;
            }
        };
        DefaultTableCellRenderer textRenderer = new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable t, Object value, boolean isSelected,
                    boolean hasFocus, int row, int column) {
                super.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
                setIcon(null);
                cellColors(this, t, isSelected, model.dimUnchecked && !model.checked[row]);
                List<String> texts = model.entries.get(row).variantTexts;
                setToolTipText(model.kind(column) == COL_VALUE && texts.size() > 1 ? String.join(", ", texts) : null);
                return this;
            }
        };
        final TableCellRenderer checks = table.getDefaultRenderer(Boolean.class);
        final JRadioButton radio = new JRadioButton();
        radio.setHorizontalAlignment(SwingConstants.CENTER);
        TableCellRenderer checkRenderer = (t, value, isSelected, hasFocus, row, column) -> {
            if (model.group != null) {
                radio.setSelected(Boolean.TRUE.equals(value));
                radio.setOpaque(true);
                radio.setBackground(isSelected ? t.getSelectionBackground() : t.getBackground());
                return radio;
            }
            return checks.getTableCellRendererComponent(t, value, isSelected, hasFocus, row, column);
        };
        if (model.group != null) {
            table.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    int row = table.rowAtPoint(e.getPoint());
                    int column = table.columnAtPoint(e.getPoint());
                    if (row >= 0 && column >= 0 && model.kind(column) == COL_CHECK) {
                        model.setValueAt(Boolean.TRUE, row, column);
                    }
                }
            });
        }
        for (int column = 0; column < model.getColumnCount(); column++) {
            int kind = model.kind(column);
            table.getColumnModel().getColumn(column).setCellRenderer(kind == COL_CHECK ? checkRenderer
                    : kind == COL_NAME || kind == COL_VALUE ? textRenderer : iconRenderer);
        }
        return table;
    }

    private static void cellColors(JComponent cell, JTable table, boolean isSelected, boolean dimmed) {
        cell.setForeground(isSelected ? table.getSelectionForeground() : dimmed ? Color.GRAY : table.getForeground());
        cell.setBackground(isSelected ? table.getSelectionBackground() : table.getBackground());
    }

    private static Icon swatch(HomeMaterial material) {
        final Icon textureIcon = material.getTexture() != null
                ? contentIcon(material.getTexture().getImage(), 22) : null;
        final Integer color = material.getColor();
        return new Icon() {
            @Override
            public void paintIcon(Component comp, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setColor(color != null ? new Color(color & 0xFFFFFF) : new Color(185, 188, 194));
                g2.fillRect(x, y, 22, 22);
                if (textureIcon != null) {
                    textureIcon.paintIcon(comp, g2, x + (22 - textureIcon.getIconWidth()) / 2,
                            y + (22 - textureIcon.getIconHeight()) / 2);
                }
                g2.setColor(Color.GRAY);
                g2.drawRect(x, y, 22, 22);
                g2.dispose();
            }

            @Override public int getIconWidth() { return 23; }

            @Override public int getIconHeight() { return 23; }
        };
    }

    private static class CheckTableModel extends AbstractTableModel {
        private static final long serialVersionUID = 1L;
        final List<MaterialUtils.MaterialEntry> entries;
        final boolean[] checked;
        private final List<Integer> kinds;
        private final String[] headers;
        private final Icon[] highlighted;
        private final Icon[] parts;
        private final Icon[] swatches;
        private final String[] names;
        private final String[] values;
        final boolean dimUnchecked;

        List<CheckTableModel> group;

        final boolean[] pending;

        boolean updatingPictures;

        CheckTableModel(List<MaterialUtils.MaterialEntry> entries, List<Integer> kinds, String[] headers,
                Icon[] highlighted, Icon[] parts, Icon[] swatches, String[] names, String[] values,
                boolean dimUnchecked) {
            this.entries = entries;
            this.checked = new boolean[entries.size()];
            this.kinds = kinds;
            this.headers = headers;
            this.highlighted = highlighted;
            this.parts = parts;
            this.swatches = swatches;
            this.names = names;
            this.values = values;
            this.pending = new boolean[entries.size()];
            Arrays.fill(this.pending, true);
            this.dimUnchecked = dimUnchecked;
        }

        int kind(int column) {
            return kinds.get(column);
        }

        void setPictures(int row, Icon highlight, Icon part) {
            highlighted[row] = highlight;
            parts[row] = part;
            pending[row] = false;
            updatingPictures = true;
            try {
                fireTableRowsUpdated(row, row);
            } finally {
                updatingPictures = false;
            }
        }

        @Override public int getRowCount() { return entries.size(); }

        @Override public int getColumnCount() { return kinds.size(); }

        @Override public String getColumnName(int column) { return headers[kind(column)]; }

        @Override
        public Class<?> getColumnClass(int column) {
            int k = kind(column);
            return k == COL_CHECK ? Boolean.class
                    : k == COL_HIGHLIGHT || k == COL_PART || k == COL_SWATCH ? Icon.class : String.class;
        }

        @Override
        public Object getValueAt(int row, int column) {
            int k = kind(column);
            return k == COL_CHECK ? checked[row] : k == COL_HIGHLIGHT ? highlighted[row]
                    : k == COL_PART ? parts[row] : k == COL_SWATCH ? swatches[row]
                    : k == COL_NAME ? names[row] : values[row];
        }

        @Override public boolean isCellEditable(int row, int column) { return kind(column) == COL_CHECK && group == null; }

        @Override
        public void setValueAt(Object value, int row, int column) {
            if (kind(column) != COL_CHECK) {
                return;
            }
            if (group == null) {
                checked[row] = Boolean.TRUE.equals(value);
                fireTableRowsUpdated(row, row);
            } else if (Boolean.TRUE.equals(value) && !checked[row]) {
                for (CheckTableModel model : group) {
                    for (int i = 0; i < model.checked.length; i++) {
                        if (model.checked[i] && (model != this || i != row)) {
                            model.checked[i] = false;
                            model.fireTableRowsUpdated(i, i);
                        }
                    }
                }
                checked[row] = true;
                fireTableRowsUpdated(row, row);
            }
        }
    }

    private static void radioGroup(JRadioButton... buttons) {
        ButtonGroup group = new ButtonGroup();
        for (JRadioButton b : buttons) {
            group.add(b);
        }
    }

    static JPanel titled(String title, JComponent content) {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createTitledBorder(title),
                new EmptyBorder(2, 6, 4, 6)));
        panel.add(content, BorderLayout.CENTER);
        return panel;
    }
}
