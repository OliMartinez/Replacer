/*
 * Copyright (c) 2023-2026 Oliver Martínez (Ollie_Jess)
 * GNU General Public License v3.0 or later.
 */
package replacer;

import com.eteks.sweethome3d.model.*;
import com.eteks.sweethome3d.plugin.Plugin;
import com.eteks.sweethome3d.plugin.PluginAction;
import com.eteks.sweethome3d.swing.IconManager;
import com.eteks.sweethome3d.tools.URLContent;
import com.eteks.sweethome3d.viewcontroller.FurnitureCatalogController;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.LayoutManager;
import java.awt.Point;
import java.awt.Rectangle;
import javax.swing.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.*;
import javax.swing.plaf.basic.BasicScrollBarUI;
import javax.swing.undo.AbstractUndoableEdit;

/**
 * Sweet Home 3D plugin for replacing furniture in the plan while preserving or randomizing properties.
 */
public class ReplacerPlugin extends Plugin {
    private JDialog replaceFrame;
    private Home boundHome;
    private FurnitureCatalogController boundCatalogController;
    private CollectionListener<HomePieceOfFurniture> furnitureListener;
    private java.awt.KeyEventDispatcher modifierDispatcher;
    private JPanel initialPanel, additionalPanel;
    private JCheckBox copyX, copyY, copyElevation, copyAngle, copyPitch, copyRoll, copyLevel;
    private JCheckBox copyWidth, copyDepth, copyHeight, copyLightPower;
    private JCheckBox copyAppearance, copyShininess, copyMirrored, copyMovable, copyRandom;
    private JCheckBox skipHiddenCheck, groupLayoutCheck, groupModelsCheck, selectMatchingInPlanCheck;
    private JRadioButton replaceGroupAsWhole, replaceGroupFurn, replaceSelectedOnly, replaceAllMatching;
    private JScrollPane initialScrollPane, additionalScrollPane;
    private JButton sizesGear, swapButton, clearReplacementButton;
    private JToggleButton appendGroupButton;

    private ReplacerSettings.ProportionalSizes proportionalSizes = new ReplacerSettings.ProportionalSizes();
    private ReplacerSettings.Alignment alignment = new ReplacerSettings.Alignment();
    private Set<HomePieceOfFurniture> initialSelectedFurniture;
    private List<Selectable> toBeDeleted;
    private List<HomePieceOfFurniture> toBeAdded;
    private List<JCheckBox> propertyChecks;
    private List<HomePieceOfFurniture> replacementPieces = new ArrayList<>();
    private SelectionListener selectionListener, catalogSelectionListener;
    private int selectedPanel;
    private HomePieceOfFurniture selectedOriginPiece;
    private ResourceBundle resourceBundle;
    private boolean appearanceWarningShown, appendModifierPressed, replacing, ignoringSelectionEvents;

    private static final String[] SEARCH_PROPERTY_KEYS = {
        "model", "objname", "x", "y", "z", "angle", "pitch", "roll", "level", "width", "depth", "height",
        "light_power", "color_texture", "shininess", "mirrored_property", "movable_property"
    };
    private static final float EPSILON = 0.001f;

    private float shininessTolerance = 0f;
    private boolean textureMatchByImage = false;

    private final ReplacerSettings.MaterialCopy[] copySettings = {
        new ReplacerSettings.MaterialCopy(), new ReplacerSettings.MaterialCopy()};

    private final ReplacerSettings.MaterialSearch[] searchSettings = {
        new ReplacerSettings.MaterialSearch(), new ReplacerSettings.MaterialSearch()};

    private ReplacerSettings.RandomRanges randomRanges = new ReplacerSettings.RandomRanges();
    private Random random = new Random();

    private ReplacerSettings.GroupLevels groupLevels = new ReplacerSettings.GroupLevels();
    private ReplacerSettings.RandomRanges activeRandomRanges;

    private List<HomePieceOfFurniture> preview;

    @Override
    public PluginAction[] getActions() {
        return new PluginAction[]{
            new PluginAction("replacer.ApplicationPlugin", "REPLACE_FURN", getPluginClassLoader(), true) {
                {
                    putPropertyValue(Property.SMALL_ICON, new URLContent(replacer.ReplacerPlugin.class.getResource("replace.png")));
                    putPropertyValue(Property.TOOL_BAR, true);

                    setEnabled(true);
                }

                @Override
                public void execute() {
                    createFrame();
                }
            }};
    }

    /** Resolves localized string from the plugin resource bundle, or returns defaultText if missing. */
    private String text(String key, String defaultText) {
        if (resourceBundle != null) {
            try {
                return resourceBundle.getString(key);
            } catch (MissingResourceException ex) {
            }
        }
        return defaultText;
    }

    /** Wraps a checkbox and an adjacent gear settings button into a unified flow panel. */
    private JPanel withGear(JCheckBox check, Icon icon, Runnable action) {
        JButton gear = gearButton(icon);
        gear.setEnabled(check.isSelected());
        check.addItemListener(e -> gear.setEnabled(check.isSelected()));
        gear.addActionListener(e -> action.run());
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        panel.add(check);
        panel.add(gear);
        return panel;
    }

    /** Groups a collection of checkboxes and a trailing gear button inside a bordered frame panel. */
    private static JPanel framed(JButton gear, JCheckBox... checks) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(200, 204, 210)), BorderFactory.createEmptyBorder(0, 2, 0, 2)));
        for (JCheckBox check : checks) {
            panel.add(check);
        }
        panel.add(gear);
        return panel;
    }

    /** Styles a compact button with small margins, bold font, and a tooltip. */
    private static <B extends AbstractButton> B smallButton(B button, String tooltip) {
        button.setMargin(new Insets(2, 6, 2, 6));
        button.setFont(new Font("SansSerif", Font.BOLD, 12));
        button.setFocusable(false);
        button.setToolTipText(tooltip);
        return button;
    }

    /** Creates a borderless gear icon button for opening modal parameter configuration dialogs. */
    private JButton gearButton(Icon icon) {
        JButton gear = new JButton(icon);
        gear.setBorderPainted(false);
        gear.setContentAreaFilled(false);
        gear.setFocusable(false);
        gear.setMargin(new Insets(0, 0, 0, 0));
        gear.setPreferredSize(new Dimension(20, 20));
        gear.setToolTipText(text("settingsTooltip", "Settings"));
        return gear;
    }

    /** Creates a styled JScrollPane with a titled border and thin (4px) horizontal scrollbar. */
    private static JScrollPane thinScrollPane(JComponent content, String title) {
        JScrollPane scrollPane = new JScrollPane(content, ScrollPaneConstants.VERTICAL_SCROLLBAR_NEVER,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        JScrollBar scrollBar = scrollPane.getHorizontalScrollBar();
        scrollBar.setPreferredSize(new Dimension(Integer.MAX_VALUE, 4));
        scrollBar.setUI(new BasicScrollBarUI() {
            @Override protected JButton createDecreaseButton(int orientation) { return createZeroButton(); }
            @Override protected JButton createIncreaseButton(int orientation) { return createZeroButton(); }

            private JButton createZeroButton() {
                JButton button = new JButton();
                Dimension zero = new Dimension(0, 0);
                button.setPreferredSize(zero);
                button.setMinimumSize(zero);
                button.setMaximumSize(zero);
                return button;
            }

            @Override
            protected void configureScrollBarColors() {
                this.thumbColor = Color.LIGHT_GRAY;
                this.trackColor = Color.WHITE;
            }
        });
        scrollPane.setBorder(BorderFactory.createTitledBorder(title));
        return scrollPane;
    }

    /** Flow panel that tracks viewport width inside JScrollPane to keep its contents centered. */
    private static class CenteredScrollablePanel extends JPanel implements Scrollable {
        CenteredScrollablePanel(LayoutManager layout) {
            super(layout);
        }

        @Override
        public boolean getScrollableTracksViewportWidth() {
            Container parent = getParent();
            return parent instanceof JViewport && parent.getWidth() > getPreferredSize().width;
        }

        @Override
        public Dimension getPreferredScrollableViewportSize() {
            return getPreferredSize();
        }

        @Override
        public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
            return 16;
        }

        @Override
        public boolean getScrollableTracksViewportHeight() {
            return true;
        }
    }

    private ImageIcon groupIcon(int size) {
        ImageIcon icon = new ImageIcon(getClass().getResource("groupIcon.png"));
        return size > 0 ? new ImageIcon(icon.getImage().getScaledInstance(size, size, Image.SCALE_SMOOTH)) : icon;
    }

    /** Combines two icons side by side separated by a horizontal gap. */
    private static Icon dualIcon(final Icon left, final Icon right, final int gap) {
        if (left == null) {
            return right;
        }
        if (right == null) {
            return left;
        }
        return new Icon() {
            @Override public int getIconWidth() { return left.getIconWidth() + gap + right.getIconWidth(); }

            @Override public int getIconHeight() { return Math.max(left.getIconHeight(), right.getIconHeight()); }

            @Override
            public void paintIcon(Component c, java.awt.Graphics g, int x, int y) {
                int h = getIconHeight();
                left.paintIcon(c, g, x, y + (h - left.getIconHeight()) / 2);
                right.paintIcon(c, g, x + left.getIconWidth() + gap, y + (h - right.getIconHeight()) / 2);
            }
        };
    }

    /** Top-down 2D plan icon rendered asynchronously off the EDT. */
    private static Icon planViewIcon(final HomePieceOfFurniture piece, final int size) {
        java.awt.image.BufferedImage cached = MaterialPreview.cachedPlanView(piece, size);
        if (cached != null) {
            return new ImageIcon(cached);
        }
        return new Icon() {
            private volatile java.awt.image.BufferedImage image;
            private volatile boolean requested;

            @Override public int getIconWidth() { return size; }

            @Override public int getIconHeight() { return size; }

            @Override
            public void paintIcon(final Component c, java.awt.Graphics g, int x, int y) {
                java.awt.image.BufferedImage img = image;
                if (img == null) {
                    img = MaterialPreview.cachedPlanView(piece, size);
                    if (img != null) {
                        image = img;
                    } else if (!requested) {
                        requested = true;
                        ReplacerDialogs.PHOTOS.submit(() -> {
                            image = MaterialPreview.planView(piece, size);
                            if (c != null) {
                                SwingUtilities.invokeLater(c::repaint);
                            }
                        });
                    }
                }
                if (img != null) {
                    g.drawImage(img, x + (size - img.getWidth()) / 2, y + (size - img.getHeight()) / 2, null);
                }
            }
        };
    }

    /** Composite icon with catalog/group preview on left and plan view on right. */
    private Icon pieceIcon(HomePieceOfFurniture piece, int size, int groupSize) {
        if (piece == null) {
            return null;
        }
        boolean isGroup = piece instanceof HomeFurnitureGroup;
        int targetSize = Math.max(16, isGroup && groupSize > 0 ? groupSize : size);
        Icon firstIcon = isGroup
                ? groupIcon(targetSize)
                : IconManager.getInstance().getIcon(piece.getIcon(), targetSize, replaceFrame);
        Icon secondIcon = planViewIcon(piece, targetSize);
        int gap = Math.max(4, targetSize / 10);
        return dualIcon(firstIcon, secondIcon, gap);
    }

    /** Miniature grid icon previewing multiple replacement pieces. */
    private Icon multiPieceIcon(List<HomePieceOfFurniture> pieces) {
        final int n = Math.min(pieces.size(), 9);
        if (n <= 0) {
            return null;
        }
        final int cols = n <= 3 ? n : n == 4 ? 2 : 3;
        final int rows = (n + cols - 1) / cols;
        final int cellSize = n == 2 ? 64 : n <= 4 ? 52 : n <= 6 ? 46 : 40;
        final int gap = n <= 4 ? 6 : 4;
        final int width = cols * cellSize + (cols - 1) * gap;
        final int height = rows * cellSize + (rows - 1) * gap;
        final List<HomePieceOfFurniture> sub = new ArrayList<>(pieces.subList(0, n));
        Icon gridIcon = new Icon() {
            private volatile boolean requested;

            @Override public int getIconWidth() { return width; }

            @Override public int getIconHeight() { return height; }

            @Override
            public void paintIcon(final Component c, java.awt.Graphics g, int ox, int oy) {
                boolean missing = false;
                for (int i = 0; i < n; i++) {
                    int row = i / cols, col = i % cols;
                    int itemsInRow = (row == rows - 1) ? (n - row * cols) : cols;
                    int startX = (width - (itemsInRow * cellSize + (itemsInRow - 1) * gap)) / 2;
                    int x = ox + startX + col * (cellSize + gap), y = oy + row * (cellSize + gap);
                    java.awt.image.BufferedImage planImg = MaterialPreview.cachedPlanView(sub.get(i), cellSize);
                    if (planImg != null) {
                        g.drawImage(planImg, x + (cellSize - planImg.getWidth()) / 2, y + (cellSize - planImg.getHeight()) / 2, null);
                    } else {
                        missing = true;
                    }
                }
                if (missing && !requested) {
                    requested = true;
                    ReplacerDialogs.PHOTOS.submit(() -> {
                        for (int i = 0; i < n; i++) {
                            MaterialPreview.planView(sub.get(i), cellSize);
                        }
                        if (c != null) {
                            SwingUtilities.invokeLater(c::repaint);
                        }
                    });
                }
            }
        };
        return dualIcon(groupIcon(96), gridIcon, 10);
    }

    private void refreshPlanPreview() {
        if (selectMatchingInPlanCheck != null && selectMatchingInPlanCheck.isSelected()) {
            updatePlanMatchPreview();
        }
    }

    private List<MaterialUtils.PieceMaterials> getOriginMaterials(int property, boolean allReplaced) {
        Collection<HomePieceOfFurniture> originals = initialSelectedFurniture != null
                ? initialSelectedFurniture : Collections.<HomePieceOfFurniture>emptySet();
        if (allReplaced) {
            List<HomePieceOfFurniture> replaced = getPreviewItemsToBeReplaced();
            if (!replaced.isEmpty()) {
                originals = replaced;
            }
        } else if (replaceGroupFurn != null && replaceGroupFurn.isSelected()) {
            Set<HomePieceOfFurniture> effective = new LinkedHashSet<>();
            for (HomePieceOfFurniture piece : originals) {
                if (piece instanceof HomeFurnitureGroup) {
                    collectReplacedGroupElements((HomeFurnitureGroup) piece, effective);
                } else {
                    effective.add(piece);
                }
            }
            if (!effective.isEmpty()) {
                originals = effective;
            }
        }
        return MaterialUtils.pieceMaterialsOf(originals, property, true);
    }

    private List<Object> defaultRandomLookChoices(ReplacerSettings.Source lookTarget) {
        return MaterialUtils.defaultLookChoices(
                copyAppearance != null && copyAppearance.isSelected(),
                copySettings[MaterialUtils.APPEARANCE],
                getOriginMaterials(MaterialUtils.APPEARANCE, true),
                MaterialUtils.pieceMaterialsOf(replacementPieces, MaterialUtils.APPEARANCE, false),
                lookTarget);
    }

    private void showCopySettings(int property, String key) {
        ReplacerSettings.MaterialCopy result = ReplacerDialogs.showMaterialCopyDialog(replaceFrame, this::text, key,
                property, copySettings[property], getOriginMaterials(property, true),
                MaterialUtils.pieceMaterialsOf(replacementPieces, property, false));
        if (result != null) {
            copySettings[property] = result;
        }
    }

    /** Unregisters all listeners from the bound Home, catalog controller, and keyboard focus manager. */
    private void detachListeners() {
        if (modifierDispatcher != null) {
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().removeKeyEventDispatcher(modifierDispatcher);
            modifierDispatcher = null;
        }
        if (boundHome != null) {
            if (selectionListener != null) {
                boundHome.removeSelectionListener(selectionListener);
            }
            if (furnitureListener != null) {
                boundHome.removeFurnitureListener(furnitureListener);
            }
            boundHome = null;
        }
        selectionListener = null;
        furnitureListener = null;
        if (boundCatalogController != null && catalogSelectionListener != null) {
            boundCatalogController.removeSelectionListener(catalogSelectionListener);
        }
        boundCatalogController = null;
        catalogSelectionListener = null;
    }

    /** Initializes and displays the modeless Replacer dialog anchored to the Sweet Home 3D window. */
    private void createFrame() {
        detachListeners();
        if (replaceFrame != null && replaceFrame.isDisplayable()) {
            replaceFrame.dispose();
        }
        ResourceBundle resource = ResourceBundle.getBundle("replacer.ApplicationPlugin", Locale.getDefault(),
                getPluginClassLoader());
        resourceBundle = resource;
        replacing = false;

        initialSelectedFurniture = getCurrentSelectedFurn();
        replacementPieces = new ArrayList<>();
        selectedPanel = initialSelectedFurniture.isEmpty() ? 1 : 2;
        selectedOriginPiece = null;
        appendModifierPressed = false;
        resetSettings();
        toBeDeleted = new ArrayList<>();
        toBeAdded = new ArrayList<>();
        ImageIcon rawIcon = new ImageIcon(getClass().getResource("Settings-icon.png"));
        Image scaledImage = rawIcon.getImage().getScaledInstance(14, 14, Image.SCALE_SMOOTH);
        ImageIcon settingsIcon = new ImageIcon(scaledImage);

        java.awt.Window owner = getHomeController() != null && getHomeController().getView() instanceof Component
                ? SwingUtilities.getWindowAncestor((Component) getHomeController().getView()) : null;
        replaceFrame = new JDialog(owner, resource.getString("REPLACE_FURN.TITLE"), java.awt.Dialog.ModalityType.MODELESS);
        replaceFrame.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
        replaceFrame.setSize(new Dimension(930, 450));
        if (owner != null) {
            replaceFrame.setLocationRelativeTo(owner);
        }

        JPanel mainPanel = new JPanel(new BorderLayout());
        replaceFrame.getContentPane().add(mainPanel);

        initialPanel = new JPanel();

        initialPanel.setLayout(new BoxLayout(initialPanel, BoxLayout.Y_AXIS));

        additionalPanel = new JPanel();
        additionalPanel.setLayout(new GridBagLayout());

        initialScrollPane = new JScrollPane(initialPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);

        initialScrollPane.addComponentListener(new java.awt.event.ComponentAdapter() {
            public void componentResized(java.awt.event.ComponentEvent e) {
                updateFirstPanel();
            }
        });

        additionalScrollPane = new JScrollPane(additionalPanel,
                JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
        updatePanelBorders();

        bindPanelSelection(1, initialScrollPane, initialScrollPane.getViewport(), initialPanel);
        bindPanelSelection(2, additionalScrollPane, additionalScrollPane.getViewport(), additionalPanel);

            appendGroupButton = smallButton(new JToggleButton("+"),
                    text("appendGroupTooltip", "Add selected objects to the replacement group (or hold Shift/Ctrl)"));
            appendGroupButton.addActionListener(e -> selectPanel(2));

            swapButton = smallButton(new JButton("\u21C4"), text("swapTooltip", "Swap origin and destination"));
            swapButton.setEnabled(false);
            swapButton.addActionListener(e -> swapSelection());

            clearReplacementButton = smallButton(new JButton("X"), text("clearReplacementTooltip", "Clear replacement"));
            clearReplacementButton.setForeground(Color.RED);
            clearReplacementButton.setEnabled(false);
            clearReplacementButton.addActionListener(e -> {
                replacementPieces.clear();
                replacementChanged();
                selectPanel(2);
                updateSecondPanel();
                refreshPlanPreview();
            });

            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, initialScrollPane, additionalScrollPane);
            splitPane.setResizeWeight(0.5);

            JLabel panelHintLabel = new JLabel(text("panelClickHint", "Click a panel to place selected objects there"), SwingConstants.CENTER);
            panelHintLabel.setFont(new Font("SansSerif", Font.PLAIN, 12));
            panelHintLabel.setForeground(new Color(90, 95, 105));
            panelHintLabel.setBorder(BorderFactory.createEmptyBorder(3, 6, 2, 6));
            mainPanel.add(panelHintLabel, BorderLayout.NORTH);
            mainPanel.add(splitPane, BorderLayout.CENTER);

            JPanel settingsPanel = new JPanel(new GridBagLayout());
            mainPanel.add(settingsPanel, BorderLayout.SOUTH);

            JPanel propertiesPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));

            JScrollPane propertiesScrollPane = thinScrollPane(propertiesPanel, resource.getString("titleCopyOptions"));

            GridBagConstraints gbc = new GridBagConstraints();

            gbc.gridx = 0;
            gbc.gridy = 0;
            gbc.weightx = 1;
            gbc.weighty = 0.0;
            gbc.fill = GridBagConstraints.BOTH;
            settingsPanel.add(propertiesScrollPane, gbc);

            copyX = new JCheckBox(resource.getString("x"), true);
            copyY = new JCheckBox(resource.getString("y"), true);
            copyElevation = new JCheckBox(resource.getString("z"), true);
            copyAngle = new JCheckBox(resource.getString("angle"), true);
            copyPitch = new JCheckBox(resource.getString("pitch"));
            copyRoll = new JCheckBox(resource.getString("roll"));
            copyLevel = new JCheckBox(resource.getString("level"), true);
            copyWidth = new JCheckBox(resource.getString("width"));
            copyDepth = new JCheckBox(resource.getString("depth"));
            copyHeight = new JCheckBox(resource.getString("height"));
            copyLightPower = new JCheckBox(resource.getString("light_power"));
            copyAppearance = new JCheckBox(text("color_texture", "Color/texture"));
            copyShininess = new JCheckBox(resource.getString("shininess"));
            copyMirrored = new JCheckBox(resource.getString("mirrored_property"));
            copyMovable = new JCheckBox(resource.getString("movable_property"), true);

            JButton positionGear = gearButton(settingsIcon);
            positionGear.setToolTipText(text("alignTitle", "Alignment"));
            positionGear.addActionListener(e -> {
                ReplacerSettings.Alignment result = ReplacerDialogs.showAlignmentDialog(replaceFrame, this::text, alignment);
                if (result != null) {
                    alignment = result;
                }
            });
            for (JCheckBox coordinate : new JCheckBox[]{copyX, copyY}) {
                coordinate.addItemListener(e -> positionGear.setEnabled(copyX.isSelected() || copyY.isSelected()));
            }
            positionGear.setEnabled(copyX.isSelected() || copyY.isSelected());
            propertiesPanel.add(framed(positionGear, copyX, copyY));
            propertiesPanel.add(copyElevation);
            propertiesPanel.add(copyAngle);
            propertiesPanel.add(copyPitch);
            propertiesPanel.add(copyRoll);
            propertiesPanel.add(copyLevel);

            sizesGear = gearButton(settingsIcon);
            sizesGear.setToolTipText(text("proportionalTitle", "Proportional dimensions"));
            sizesGear.addActionListener(e -> {
                ReplacerSettings.ProportionalSizes result = ReplacerDialogs.showProportionalDialog(replaceFrame,
                        this::text, proportionalSizes, new JCheckBox[]{copyWidth, copyDepth, copyHeight});
                if (result != null) {
                    proportionalSizes = result;
                }
            });
            for (JCheckBox size : new JCheckBox[]{copyWidth, copyDepth, copyHeight}) {
                size.addItemListener(e -> sizesGear.setEnabled(checkedSizes() == 1 || checkedSizes() == 2));
            }
            sizesGear.setEnabled(false);
            propertiesPanel.add(framed(sizesGear, copyWidth, copyDepth, copyHeight));
            propertiesPanel.add(copyLightPower);

            propertiesPanel.add(withGear(copyAppearance, settingsIcon, () -> showCopySettings(MaterialUtils.APPEARANCE, "color_texture")));
            propertiesPanel.add(withGear(copyShininess, settingsIcon, () -> showCopySettings(MaterialUtils.SHININESS, "shininess")));
            propertiesPanel.add(copyMirrored);
            propertiesPanel.add(copyMovable);

            copyRandom = new JCheckBox(text("randomOption", "Random values"));
            JPanel randomPanel = withGear(copyRandom, settingsIcon, () -> {
                ReplacerSettings.RandomRanges r = ReplacerDialogs.showRandomDialog(replaceFrame, this::text, randomRanges,
                        MaterialUtils.pieceMaterialsOf(replacementPieces, MaterialUtils.APPEARANCE, false),
                        this::defaultRandomLookChoices,
                        getUserPreferences(), replacementRotatable(), containsLight(replacementPieces),
                        replacementObjects());
                if (r != null) {
                    randomRanges = r;
                    if (r.anyEnabled()) {
                        copyRandom.setSelected(true);
                    }
                }
            });

            JPanel optionsPanel = new JPanel(new GridLayout(1, 2));

            JPanel groupOptionsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
            JScrollPane groupOptionsScroll = thinScrollPane(groupOptionsPanel, resource.getString("titleGroupOptions"));
            optionsPanel.add(groupOptionsScroll);
            JPanel replaceOptionsPanel = new CenteredScrollablePanel(new FlowLayout(FlowLayout.CENTER, 4, 1));
            JScrollPane replaceOptionsScroll = thinScrollPane(replaceOptionsPanel, resource.getString("titleReplaceOptions"));
            optionsPanel.add(replaceOptionsScroll);

            gbc.gridy = 1;
            settingsPanel.add(optionsPanel, gbc);

            replaceGroupFurn = new JRadioButton(resource.getString("groupFurn"), true);
            replaceGroupAsWhole = new JRadioButton(resource.getString("groupAsWhole"));

            ButtonGroup groupOptions = new ButtonGroup();
            groupOptions.add(replaceGroupFurn);
            groupOptions.add(replaceGroupAsWhole);

            groupOptionsPanel.add(replaceGroupAsWhole);

            JButton groupSettingsBtn = gearButton(settingsIcon);
            groupSettingsBtn.addActionListener(e -> {
                ReplacerSettings.GroupLevels levels = ReplacerDialogs.showGroupLevelsDialog(replaceFrame, this::text, groupLevels);
                if (levels != null) {
                    groupLevels = levels;
                    updateLightPowerOption();
                    refreshPlanPreview();
                }
            });

            JPanel replaceGroupPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
            replaceGroupPanel.add(replaceGroupFurn);
            replaceGroupPanel.add(groupSettingsBtn);
            groupOptionsPanel.add(replaceGroupPanel);

            JPanel groupModelsPanel = new JPanel(new BorderLayout());
            replaceGroupFurn.addItemListener(e -> {
                boolean groupFurnSelected = replaceGroupFurn.isSelected();
                groupSettingsBtn.setEnabled(groupFurnSelected);
                groupModelsPanel.setVisible(groupFurnSelected);
                if (!groupFurnSelected && groupModelsCheck != null) {
                    groupModelsCheck.setSelected(false);
                }
                updateLightPowerOption();
                refreshPlanPreview();
            });

            replaceSelectedOnly = new JRadioButton(resource.getString("selectedOnly"), true);
            replaceAllMatching = new JRadioButton(resource.getString("allMatching"));
            ButtonGroup replaceOptions = new ButtonGroup();
            replaceOptions.add(replaceSelectedOnly);
            replaceOptions.add(replaceAllMatching);
            replaceOptionsPanel.add(replaceSelectedOnly);

            JPanel buttonPanel = new JPanel(new FlowLayout());
            gbc.gridy = 2;
            settingsPanel.add(buttonPanel, gbc);

            JButton replaceAllSettingsBtn = gearButton(settingsIcon);
            replaceAllSettingsBtn.setEnabled(false);

            selectMatchingInPlanCheck = new JCheckBox(text("selectMatchingInPlan", "View matches"));
            selectMatchingInPlanCheck.setToolTipText(text("selectMatchingInPlanTip",
                    "Highlights in the 2D plan view all objects that will be replaced."));
            selectMatchingInPlanCheck.setVisible(false);
            selectMatchingInPlanCheck.addActionListener(e -> updatePlanMatchPreview());

            JPanel replaceAllPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
            replaceAllPanel.setOpaque(false);
            replaceAllPanel.add(replaceAllMatching);
            replaceAllPanel.add(replaceAllSettingsBtn);
            replaceOptionsPanel.add(replaceAllPanel);
            replaceOptionsPanel.add(selectMatchingInPlanCheck);

            replaceAllMatching.addItemListener(e -> {
                boolean allMatch = replaceAllMatching.isSelected();
                replaceAllSettingsBtn.setEnabled(allMatch);
                selectMatchingInPlanCheck.setVisible(allMatch);
                if (!allMatch && selectMatchingInPlanCheck.isSelected()) {
                    selectMatchingInPlanCheck.setSelected(false);
                }
                replaceOptionsPanel.revalidate();
                replaceOptionsPanel.repaint();
                updatePlanMatchPreview();
            });

            replaceSelectedOnly.addItemListener(e -> {
                if (replaceSelectedOnly.isSelected()) {
                    selectMatchingInPlanCheck.setVisible(false);
                    if (selectMatchingInPlanCheck.isSelected()) {
                        selectMatchingInPlanCheck.setSelected(false);
                    }
                    replaceAllSettingsBtn.setEnabled(false);
                    replaceOptionsPanel.revalidate();
                    replaceOptionsPanel.repaint();
                    updatePlanMatchPreview();
                }
            });

            JDialog replaceAllDialog = new JDialog(replaceFrame, resource.getString("titleSearchOptions"), false);
            replaceAllDialog.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);
            replaceAllDialog.setSize(300, 440);
            replaceAllDialog.setLayout(new BorderLayout());

            JPanel checkPanel = new JPanel();
            checkPanel.setLayout(new BoxLayout(checkPanel, BoxLayout.Y_AXIS));
            checkPanel.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

            propertyChecks = new ArrayList<>();
            for (String key : SEARCH_PROPERTY_KEYS) {
                JCheckBox check = new JCheckBox(text(key, key));
                check.setSelected("model".equals(key));

                JPanel rowPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
                rowPanel.add(check);

                if (key.equals("color_texture") || key.equals("shininess")) {
                    JButton itemSettingsBtn = gearButton(settingsIcon);
                    itemSettingsBtn.setEnabled(check.isSelected());

                    check.addItemListener(e -> itemSettingsBtn.setEnabled(check.isSelected()));
                    itemSettingsBtn.addActionListener(e -> showSearchSettings(replaceAllDialog, key));
                    itemSettingsBtn.setToolTipText(text("searchSettingsTitle", "Search settings"));

                    rowPanel.add(itemSettingsBtn);
                }

                checkPanel.add(rowPanel);
                propertyChecks.add(check);
            }

            groupLayoutCheck = new JCheckBox(ReplacerDialogs.wrap(text("groupLayoutMatters",
                    "Require identical internal layout"), 185));
            JPanel layoutItemPanel = ReplacerDialogs.withInfo(groupLayoutCheck, text("groupLayoutHint",
                    "Matches relative position, elevation, angle, and dimensions of each element."));

            groupModelsCheck = new JCheckBox(ReplacerDialogs.wrap(text("matchGroupModels",
                    "Match each 3D model in selected groups"), 185));
            groupModelsPanel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));
            groupModelsPanel.add(ReplacerDialogs.withInfo(groupModelsCheck, text("matchGroupModelsHint",
                    "Applies subgroup depth settings from «Replace group elements».")), BorderLayout.NORTH);
            groupModelsPanel.setVisible(replaceGroupFurn.isSelected());

            JPanel layoutPanel = new JPanel(new BorderLayout());
            layoutPanel.add(layoutItemPanel, BorderLayout.NORTH);
            layoutPanel.add(groupModelsPanel, BorderLayout.SOUTH);

            Component layoutSpace = Box.createVerticalStrut(8);
            JPanel layoutSection = ReplacerDialogs.titled(text("matchingGroupsTitle", "Group matching"), layoutPanel);
            checkPanel.add(layoutSpace);
            checkPanel.add(layoutSection);

            skipHiddenCheck = new JCheckBox(ReplacerDialogs.wrap(text("skipHidden",
                    "Skip hidden or locked furniture"), 200), true);
            JPanel skipPanel = new JPanel(new BorderLayout());
            skipPanel.add(skipHiddenCheck, BorderLayout.CENTER);
            checkPanel.add(Box.createVerticalStrut(6));
            checkPanel.add(skipPanel);

            JScrollPane scroll = new JScrollPane(checkPanel);
            scroll.setBorder(null);
            replaceAllDialog.add(scroll, BorderLayout.CENTER);

            final List<JCheckBox> searchChecks = new ArrayList<>(propertyChecks);
            searchChecks.add(groupLayoutCheck);
            searchChecks.add(groupModelsCheck);
            searchChecks.add(skipHiddenCheck);
            final boolean[] savedChecks = new boolean[searchChecks.size()];
            Runnable restoreChecks = () -> {
                for (int i = 0; i < searchChecks.size(); i++) {
                    searchChecks.get(i).setSelected(savedChecks[i]);
                }
                updateLightPowerOption();
            };

            JButton acceptBtn = new JButton(text("accept", "OK"));
            acceptBtn.addActionListener(e -> {
                replaceAllDialog.dispose();
                refreshPlanPreview();
            });
            JButton cancelBtn = new JButton(text("cancel", "Cancel"));
            cancelBtn.addActionListener(e -> {
                restoreChecks.run();
                replaceAllDialog.dispose();
            });
            replaceAllDialog.addWindowListener(new WindowAdapter() {
                @Override
                public void windowClosing(WindowEvent e) {
                    restoreChecks.run();
                }
            });
            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            btnPanel.add(cancelBtn);
            btnPanel.add(acceptBtn);
            replaceAllDialog.add(btnPanel, BorderLayout.SOUTH);

            replaceAllSettingsBtn.addActionListener(e -> {
                updateLightPowerOption();
                for (int i = 0; i < searchChecks.size(); i++) {
                    savedChecks[i] = searchChecks.get(i).isSelected();
                }
                boolean groups = involvesGroups();
                layoutSpace.setVisible(groups);
                layoutSection.setVisible(groups);
                groupModelsPanel.setVisible(replaceGroupFurn.isSelected());
                if (!replaceGroupFurn.isSelected()) {
                    groupModelsCheck.setSelected(false);
                }
                Point location = replaceAllSettingsBtn.getLocationOnScreen();
                replaceAllDialog.setLocation(location.x, location.y + replaceAllSettingsBtn.getHeight());
                replaceAllDialog.setVisible(true);
            });

            JButton replaceButton = new JButton(resource.getString("undoReplace"));
            replaceButton.addActionListener(e -> {
                if (initialSelectedFurniture.isEmpty()) {
                    message(text("emptyOriginMessage", "There is no furniture left to replace."),
                            resource.getString("ERROR_TITLE"), JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (replacementPieces.isEmpty()) {
                    message(text("noReplacementMessage", "Select in the plan or furniture list the piece to use as replacement."),
                            resource.getString("ERROR_TITLE"), JOptionPane.WARNING_MESSAGE);
                    return;
                }
                if (replaceWithUndo(resource) == 0) {
                    message(text("nothingReplacedMessage", "No furniture matched the search criteria."),
                            resource.getString("completed"), JOptionPane.INFORMATION_MESSAGE);
                    return;
                }
                if (activeRandomRanges == null) {
                    message(resource.getString("success"), resource.getString("completed"), JOptionPane.INFORMATION_MESSAGE);
                } else {
                    Object[] options = {text("accept", "OK"), text("reroll", "Draw again")};
                    while (JOptionPane.showOptionDialog(replaceFrame, resource.getString("success"),
                            resource.getString("completed"), JOptionPane.DEFAULT_OPTION, JOptionPane.INFORMATION_MESSAGE,
                            null, options, options[0]) == 1) {
                        replacing = true;
                        try {
                            getHomeController().undo();
                        } finally {
                            replacing = false;
                        }
                        replaceWithUndo(resource);
                    }
                }
                replaceFrame.dispose();
            });
            buttonPanel.add(randomPanel);
            buttonPanel.add(Box.createHorizontalStrut(24));
            buttonPanel.add(replaceButton);

            JPanel donatePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            gbc.gridy = 3;
            gbc.weighty = 0.0;
            settingsPanel.add(donatePanel, gbc);

            JLabel donateLabel = new JLabel(resource.getString("donateLabel"));
            donatePanel.add(donateLabel);

            JButton donateButton = new JButton(resource.getString("donateButton"));
            donatePanel.add(donateButton);

            donateButton.addActionListener(e -> {
                try {
                    Desktop.getDesktop().browse(new URI("https://www.paypal.com/donate/?hosted_button_id=4MAYRTRRMQYE2"));
                } catch (IOException | URISyntaxException ex) {
                    JOptionPane.showMessageDialog(replaceFrame, resource.getString("donateError"), "Error", JOptionPane.ERROR_MESSAGE);
                    ex.printStackTrace();
                }
            });

            final Home currentHome = getHome();
            final FurnitureCatalogController catalogController = getHomeController() != null
                    ? getHomeController().getFurnitureCatalogController() : null;
            if (catalogController != null) {
                withoutSelectionEvents(() -> catalogController.setSelectedFurniture(Collections.<CatalogPieceOfFurniture>emptyList()));
            }
            final SelectionListener frameSelectionListener = selectionEvent -> {
                if (!replacing && !ignoringSelectionEvents) {
                    if (selectMatchingInPlanCheck.isSelected()) {
                        selectMatchingInPlanCheck.setSelected(false);
                    }
                    List<HomePieceOfFurniture> selected = new ArrayList<>(getCurrentSelectedFurn());
                    if (selected.isEmpty()) {
                        return;
                    }
                    if (catalogController != null) {
                        withoutSelectionEvents(() -> catalogController.setSelectedFurniture(Collections.<CatalogPieceOfFurniture>emptyList()));
                    }
                    if (getTargetPanel() == 1) {
                        initialSelectedFurniture.addAll(selected);
                        selectedOriginPiece = selected.get(selected.size() - 1);
                        updateFirstPanel();
                        updateLightPowerOption();
                        refreshPlanPreview();
                    } else {
                        applySecondPanelSelection(selected, false);
                    }
                }
            };
            final SelectionListener frameCatalogListener = selectionEvent -> {
                if (!replacing && !ignoringSelectionEvents && catalogController != null) {
                    List<CatalogPieceOfFurniture> catalogSelected = catalogController.getSelectedFurniture();
                    if (catalogSelected == null || catalogSelected.isEmpty()) {
                        return;
                    }
                    if (selectMatchingInPlanCheck.isSelected()) {
                        selectMatchingInPlanCheck.setSelected(false);
                    }
                    if (currentHome != null) {
                        withoutSelectionEvents(() -> currentHome.setSelectedItems(Collections.<Selectable>emptyList()));
                    }
                    selectedOriginPiece = null;
                    updateFirstPanel();
                    List<HomePieceOfFurniture> selected = new ArrayList<>(catalogSelected.size());
                    for (CatalogPieceOfFurniture catalogPiece : catalogSelected) {
                        selected.add(createHomePiece(catalogPiece));
                    }
                    selectPanel(2);
                    applySecondPanelSelection(selected, true);
                }
            };
            modifierDispatcher = e -> {
                appendModifierPressed = e.isShiftDown() || e.isControlDown() || e.isMetaDown();
                return false;
            };
            java.awt.KeyboardFocusManager.getCurrentKeyboardFocusManager().addKeyEventDispatcher(modifierDispatcher);

            furnitureListener = ev -> {
                if (!replacing && ev.getType() == CollectionEvent.Type.DELETE) {
                    HomePieceOfFurniture deleted = ev.getItem();
                    SwingUtilities.invokeLater(() -> dropDeletedPiece(deleted));
                }
            };
            boundHome = currentHome;
            boundCatalogController = catalogController;
            if (boundHome != null) {
                boundHome.addFurnitureListener(furnitureListener);
                selectionListener = frameSelectionListener;
                boundHome.addSelectionListener(frameSelectionListener);
            }
            catalogSelectionListener = frameCatalogListener;
            if (boundCatalogController != null) {
                boundCatalogController.addSelectionListener(frameCatalogListener);
            }
            replaceFrame.addWindowListener(new WindowAdapter() {
                @Override
                public void windowActivated(WindowEvent e) {
                    if (!replacing) {
                        updateFirstPanel();
                        updateSecondPanel();
                    }
                }

                @Override
                public void windowClosed(WindowEvent e) {
                    detachListeners();
                }
            });

            updateFirstPanel();
            updateSecondPanel();

            int neededWidth = 2 * Math.max(groupOptionsScroll.getPreferredSize().width, replaceOptionsScroll.getPreferredSize().width) + 40;
            int screenWidth = java.awt.Toolkit.getDefaultToolkit().getScreenSize().width;
            if (neededWidth > replaceFrame.getWidth()) {
                replaceFrame.setSize(Math.min(neededWidth, screenWidth - 40), replaceFrame.getHeight());
            }
            if (owner != null) {
                replaceFrame.setLocationRelativeTo(owner);
            }

            int propsHeight = 58;
            propertiesScrollPane.setPreferredSize(new Dimension(propertiesScrollPane.getPreferredSize().width, propsHeight));
            propertiesScrollPane.setMinimumSize(new Dimension(propertiesScrollPane.getMinimumSize().width, propsHeight));
            optionsPanel.setMinimumSize(new Dimension(optionsPanel.getMinimumSize().width, optionsPanel.getPreferredSize().height));
            replaceFrame.setVisible(true);

            SwingUtilities.invokeLater(() -> {
                updateFirstPanel();
                updateSecondPanel();
            });
    }

    private void message(String message, String title, int type) {
        JOptionPane.showMessageDialog(replaceFrame, message, title, type);
    }

    private void resetSettings() {
        for (int property = 0; property < copySettings.length; property++) {
            copySettings[property] = new ReplacerSettings.MaterialCopy();
            searchSettings[property] = new ReplacerSettings.MaterialSearch();
        }
        randomRanges = new ReplacerSettings.RandomRanges();
        alignment = new ReplacerSettings.Alignment();
        proportionalSizes = new ReplacerSettings.ProportionalSizes();
        groupLevels = new ReplacerSettings.GroupLevels();
        shininessTolerance = 0f;
        textureMatchByImage = false;
    }

    private Set<HomePieceOfFurniture> getCurrentSelectedFurn() {
        Set<HomePieceOfFurniture> selectedFurniture = new LinkedHashSet<>();
        for (Selectable item : getHome().getSelectedItems()) {
            if (item instanceof HomePieceOfFurniture) {
                selectedFurniture.add((HomePieceOfFurniture) item);
            }
        }
        return selectedFurniture;
    }

    private static boolean containsLight(Collection<HomePieceOfFurniture> furniture) {
        for (HomePieceOfFurniture piece : furniture) {
            if (firstLight(piece) != null) {
                return true;
            }
        }
        return false;
    }

    private boolean isHiddenOrLocked(HomePieceOfFurniture piece) {
        return !piece.isVisible()
                || piece.getLevel() != null && !piece.getLevel().isViewable()
                || getHome().isBasePlanLocked() && (piece.isDoorOrWindow() || !piece.isMovable());
    }

    private void replacementChanged() {
        for (ReplacerSettings.MaterialCopy settings : copySettings) {
            settings.toMaterials.clear();
            settings.toWhole = true;
        }
        randomRanges.shininessTarget = new ReplacerSettings.Source();
        randomRanges.look.target = new ReplacerSettings.Source();
        randomRanges.pieceWeights = new ArrayList<>();
    }

    private void withoutSelectionEvents(Runnable action) {
        ignoringSelectionEvents = true;
        try {
            action.run();
        } finally {
            ignoringSelectionEvents = false;
        }
    }

    private void bindPanelSelection(final int panel, JComponent... components) {
        java.awt.event.MouseAdapter listener = new java.awt.event.MouseAdapter() {
            @Override
            public void mousePressed(java.awt.event.MouseEvent e) {
                selectPanel(panel);
            }
        };
        for (JComponent component : components) {
            component.addMouseListener(listener);
        }
    }

    private JLabel panelHint(String content, int panel) {
        JLabel label = new JLabel("<html><div style='text-align:center;width:260px'>" + content + "</div></html>", SwingConstants.CENTER);
        label.setForeground(new Color(110, 116, 126));
        bindPanelSelection(panel, label);
        return label;
    }

    private void selectPanel(int panel) {
        if (selectedPanel != panel) {
            selectedPanel = panel;
            updatePanelBorders();
        }
    }

    private boolean isAppendModifierActive() {
        java.awt.AWTEvent ev = java.awt.EventQueue.getCurrentEvent();
        if (ev instanceof java.awt.event.InputEvent) {
            java.awt.event.InputEvent ie = (java.awt.event.InputEvent) ev;
            return ie.isShiftDown() || ie.isControlDown() || ie.isMetaDown();
        }
        return appendModifierPressed;
    }

    private void applySecondPanelSelection(List<HomePieceOfFurniture> selected, boolean fromCatalog) {
        boolean append = (appendGroupButton != null && appendGroupButton.isSelected()) || isAppendModifierActive();
        List<HomePieceOfFurniture> updated;
        if (append && !replacementPieces.isEmpty()) {
            updated = new ArrayList<>(replacementPieces);
            for (HomePieceOfFurniture piece : selected) {
                if (fromCatalog || !updated.contains(piece)) {
                    updated.add(piece);
                }
            }
        } else {
            updated = selected;
        }
        if (!updated.equals(replacementPieces)) {
            replacementPieces = updated;
            replacementChanged();
        }
        updateSecondPanel();
        refreshPlanPreview();
    }

    private int getTargetPanel() {
        if (selectedPanel == 1 || selectedPanel == 2) {
            return selectedPanel;
        }
        return initialSelectedFurniture == null || initialSelectedFurniture.isEmpty() ? 1 : 2;
    }

    private void updatePanelBorders() {
        if (initialScrollPane != null) {
            initialScrollPane.setBorder(panelBorder(text("PANEL1", "Objects to replace:"), selectedPanel == 1));
        }
        if (additionalScrollPane != null) {
            additionalScrollPane.setBorder(panelBorder(text("PANEL2", "Replace with:"), selectedPanel == 2));
        }
    }

    private static javax.swing.border.Border panelBorder(String title, boolean selected) {
        if (!selected) {
            return BorderFactory.createTitledBorder(title);
        }
        Color accent = new Color(0, 120, 215);
        return BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(accent, 2),
                title,
                javax.swing.border.TitledBorder.LEADING,
                javax.swing.border.TitledBorder.TOP,
                UIManager.getFont("TitledBorder.font"),
                accent);
    }

    private HomePieceOfFurniture createHomePiece(PieceOfFurniture piece) {
        if (piece instanceof HomePieceOfFurniture) {
            HomePieceOfFurniture hp = (HomePieceOfFurniture) piece;
            if (hp.getWidthInPlan() <= 0 || hp.getDepthInPlan() <= 0 || hp.getHeightInPlan() <= 0) {
                updateSizeInPlan(hp);
            }
            return hp;
        }
        HomePieceOfFurniture created;
        if (getHomeController() != null && getHomeController().getFurnitureController() != null) {
            created = getHomeController().getFurnitureController().createHomePieceOfFurniture(piece);
        } else if (piece instanceof DoorOrWindow) {
            created = new HomeDoorOrWindow((DoorOrWindow) piece);
        } else if (piece instanceof Light) {
            created = new HomeLight((Light) piece);
        } else if (piece instanceof ShelfUnit) {
            created = new HomeShelfUnit((ShelfUnit) piece);
        } else {
            created = new HomePieceOfFurniture(piece);
        }
        updateSizeInPlan(created);
        return created;
    }

    private Set<HomePieceOfFurniture> getHomeFurnitureSet() {
        Set<HomePieceOfFurniture> inHome = new HashSet<>();
        if (getHome() != null) {
            for (HomePieceOfFurniture piece : getHome().getFurniture()) {
                inHome.add(piece);
                if (piece instanceof HomeFurnitureGroup) {
                    inHome.addAll(((HomeFurnitureGroup) piece).getAllFurniture());
                }
            }
        }
        return inHome;
    }

    private List<HomePieceOfFurniture> filterInHome(Collection<HomePieceOfFurniture> items) {
        Set<HomePieceOfFurniture> inHome = getHomeFurnitureSet();
        List<HomePieceOfFurniture> result = new ArrayList<>();
        for (HomePieceOfFurniture piece : items) {
            if (inHome.contains(piece)) {
                result.add(piece);
            }
        }
        return result;
    }

    private void selectPieceInPlan(HomePieceOfFurniture piece) {
        if (piece == null || getHome() == null) {
            return;
        }
        withoutSelectionEvents(() -> {
            if (selectMatchingInPlanCheck != null && selectMatchingInPlanCheck.isSelected()) {
                selectMatchingInPlanCheck.setSelected(false);
            }
            if (getHomeFurnitureSet().contains(piece)) {
                getHome().setSelectedItems(Collections.singletonList(piece));
            }
        });
    }

    private void dropDeletedPiece(HomePieceOfFurniture deleted) {
        if (replaceFrame == null || !replaceFrame.isDisplayable() || deleted == null) {
            return;
        }
        Set<HomePieceOfFurniture> deletedSet = new HashSet<>();
        deletedSet.add(deleted);
        if (deleted instanceof HomeFurnitureGroup) {
            deletedSet.addAll(((HomeFurnitureGroup) deleted).getAllFurniture());
        }
        deletedSet.removeAll(getHomeFurnitureSet());
        boolean originChanged = initialSelectedFurniture.removeAll(deletedSet);
        if (selectedOriginPiece != null && deletedSet.contains(selectedOriginPiece)) {
            selectedOriginPiece = null;
        }
        boolean replacementChanged = replacementPieces.removeAll(deletedSet);
        if (replacementChanged) {
            replacementChanged();
            updateSecondPanel();
        }
        if (originChanged) {
            if (initialSelectedFurniture.isEmpty()) {
                selectPanel(1);
            }
            updateFirstPanel();
            updateLightPowerOption();
        }
        if (originChanged || replacementChanged) {
            refreshPlanPreview();
        }
    }

    private boolean involvesGroups() {
        for (HomePieceOfFurniture piece : initialSelectedFurniture) {
            if (piece instanceof HomeFurnitureGroup) {
                return true;
            }
        }
        Home home = getHome();
        if (home == null || initialSelectedFurniture.isEmpty()) {
            return false;
        }
        Map<HomePieceOfFurniture, HomeFurnitureGroup> parents = new HashMap<>();
        fillParents(home.getFurniture(), null, parents);
        for (HomePieceOfFurniture piece : initialSelectedFurniture) {
            if (parents.containsKey(piece)) {
                return true;
            }
        }
        return false;
    }

    private int checkedSizes() {
        return (copyWidth.isSelected() ? 1 : 0) + (copyDepth.isSelected() ? 1 : 0) + (copyHeight.isSelected() ? 1 : 0);
    }

    private HomePieceOfFurniture getReplacement() {
        if (replacementPieces.isEmpty()) {
            return null;
        }
        if (replacementPieces.size() == 1) {
            return replacementPieces.get(0);
        }
        List<HomePieceOfFurniture> copies = new ArrayList<>();
        for (HomePieceOfFurniture piece : replacementPieces) {
            HomePieceOfFurniture copy = piece.clone();
            updateSizeInPlan(copy);
            copies.add(copy);
        }
        return new HomeFurnitureGroup(copies, text("replacementGroupName", "Replacement group"));
    }

    private List<HomePieceOfFurniture> replacementObjects() {
        if (replacementPieces.size() > 1) {
            return replacementPieces;
        }
        if (replacementPieces.size() == 1 && replacementPieces.get(0) instanceof HomeFurnitureGroup) {
            return ((HomeFurnitureGroup) replacementPieces.get(0)).getFurniture();
        }
        return Collections.emptyList();
    }

    private boolean isReplacementSource(HomePieceOfFurniture piece) {
        return replacementPieces.contains(piece);
    }

    private static void setCheckAvailable(JCheckBox check, boolean available, String disabledTooltip) {
        check.setEnabled(available);
        if (!available) {
            check.setSelected(false);
        }
        check.setToolTipText(available ? null : disabledTooltip);
    }

    private static void setSearchCheckVisible(JCheckBox check, boolean visible) {
        if (!visible) {
            check.setSelected(false);
        }
        check.setVisible(visible);
        Container parent = check.getParent();
        if (parent instanceof JPanel && parent.getComponentCount() == 1) {
            parent.setVisible(visible);
        }
        if (parent != null) {
            parent.revalidate();
            parent.repaint();
        }
    }

    private void updateReplacementConflicts() {
        if (copyPitch == null) {
            return;
        }
        boolean rotatable = replacementRotatable();
        String tip = text("notRotatableTip", "The replacement (a group or a piece that can't be rotated this way) doesn't allow this rotation.");
        setCheckAvailable(copyPitch, rotatable, tip);
        setCheckAvailable(copyRoll, rotatable, tip);
    }

    private boolean replacementRotatable() {
        boolean rotatable = replacementPieces.size() <= 1;
        for (HomePieceOfFurniture piece : replacementPieces) {
            rotatable &= piece.isHorizontallyRotatable();
        }
        return rotatable;
    }

    private boolean originContainsLight() {
        boolean insideGroupMode = replaceGroupFurn != null && replaceGroupFurn.isSelected();
        for (HomePieceOfFurniture piece : initialSelectedFurniture) {
            if (insideGroupMode && piece instanceof HomeFurnitureGroup) {
                Set<HomePieceOfFurniture> elements = new LinkedHashSet<>();
                collectReplacedGroupElements((HomeFurnitureGroup) piece, elements);
                if (containsLight(elements)) {
                    return true;
                }
            } else if (firstLight(piece) != null) {
                return true;
            }
        }
        return false;
    }

    private void updateLightPowerOption() {
        boolean originLight = originContainsLight();
        boolean replacementLight = containsLight(replacementPieces);
        String noOriginTip = text("noLightTip", "None of the pieces to replace is a light.");
        if (copyLightPower != null) {
            setCheckAvailable(copyLightPower, originLight && replacementLight,
                    !originLight ? noOriginTip : text("noReplacementLightTip", "The replacement has no light."));
        }
        if (propertyChecks != null) {
            int lightIndex = Arrays.asList(SEARCH_PROPERTY_KEYS).indexOf("light_power");
            if (lightIndex >= 0 && lightIndex < propertyChecks.size()) {
                setSearchCheckVisible(propertyChecks.get(lightIndex), originLight);
            }
        }
        if (!replacementLight && randomRanges != null) {
            int lightIndex = randomRanges.indexOf("light_power");
            if (lightIndex >= 0) {
                randomRanges.enabled[lightIndex] = false;
            }
        }
    }

    /** Rebuilds the left panel listing the furniture pieces to be replaced. */
    private void updateFirstPanel() {
        initialPanel.removeAll();

        int totalHeight = initialScrollPane.getViewport().getHeight();
        if (totalHeight <= 0) totalHeight = 400;

        int count = initialSelectedFurniture.size();
        if (count == 0) {
            JLabel hint = panelHint(text("emptyOriginHint", "Select in the plan or in the furniture list the pieces to replace."), 1);
            hint.setAlignmentX(Component.CENTER_ALIGNMENT);
            initialPanel.add(Box.createVerticalGlue());
            initialPanel.add(hint);
            initialPanel.add(Box.createVerticalGlue());
            initialPanel.revalidate();
            initialPanel.repaint();
            checkSwapAvailability();
            return;
        }
        int fontSize = 14;

        int iconSize = Math.max(32, Math.min(128, totalHeight / count - 30));

        for (HomePieceOfFurniture piece : initialSelectedFurniture) {
            Icon icon = pieceIcon(piece, iconSize, iconSize);

            final boolean isSelectedRow = piece == selectedOriginPiece;
            final Color selectedBg = new Color(205, 226, 247);
            final Color hoverBg = new Color(230, 230, 230);
            JPanel rowPanel = new JPanel(new BorderLayout(5, 0));
            rowPanel.setOpaque(isSelectedRow);
            if (isSelectedRow) {
                rowPanel.setBackground(selectedBg);
            }

            rowPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, iconSize + 10));
            rowPanel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));

            JLabel infoLabel = new JLabel(piece.getName(), icon, JLabel.LEFT);
            infoLabel.setFont(new Font("SansSerif", Font.PLAIN, fontSize));

            JButton deleteBtn = smallButton(new JButton("X"), text("removeTooltip", "Remove from list"));
            deleteBtn.setForeground(Color.RED);

            deleteBtn.addActionListener(e -> {
                initialSelectedFurniture.remove(piece);
                if (selectedOriginPiece == piece) {
                    selectedOriginPiece = null;
                }
                if (initialSelectedFurniture.isEmpty()) {
                    selectPanel(1);
                }

                updateFirstPanel();
                updateLightPowerOption();
                refreshPlanPreview();
            });

            java.awt.event.MouseAdapter rowInteraction = new java.awt.event.MouseAdapter() {
                @Override
                public void mousePressed(java.awt.event.MouseEvent e) {
                    if (e.getSource() != deleteBtn) {
                        selectedOriginPiece = piece;
                        selectPieceInPlan(piece);
                        updateFirstPanel();
                    }
                }
                @Override
                public void mouseEntered(java.awt.event.MouseEvent e) {
                    rowPanel.setOpaque(true);
                    rowPanel.setBackground(piece == selectedOriginPiece ? selectedBg : hoverBg);
                    rowPanel.repaint();
                }
                @Override
                public void mouseExited(java.awt.event.MouseEvent e) {
                    rowPanel.setOpaque(piece == selectedOriginPiece);
                    if (piece == selectedOriginPiece) {
                        rowPanel.setBackground(selectedBg);
                    }
                    rowPanel.repaint();
                }
            };
            rowPanel.addMouseListener(rowInteraction);
            infoLabel.addMouseListener(rowInteraction);
            deleteBtn.addMouseListener(rowInteraction);

            rowPanel.add(infoLabel, BorderLayout.CENTER);
            rowPanel.add(deleteBtn, BorderLayout.EAST);

            initialPanel.add(rowPanel);

            initialPanel.add(Box.createVerticalStrut(5));
        }

        initialPanel.revalidate();
        initialPanel.repaint();
        checkSwapAvailability();
    }

    /** Rebuilds the right panel displaying the replacement piece or group. */
    private void updateSecondPanel() {
        additionalPanel.removeAll();
        GridBagConstraints gbc = new GridBagConstraints();

        if (swapButton != null) {
            JPanel topButtons = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            topButtons.setOpaque(false);
            if (appendGroupButton != null) {
                topButtons.add(appendGroupButton);
            }
            topButtons.add(swapButton);
            if (clearReplacementButton != null) {
                topButtons.add(clearReplacementButton);
            }
            gbc.gridx = 0; gbc.gridy = 0;
            gbc.weightx = 1; gbc.anchor = GridBagConstraints.EAST;
            gbc.insets = new Insets(0, 0, 5, 5);
            additionalPanel.add(topButtons, gbc);
        }

        HomePieceOfFurniture firstAdditionalFurniture = replacementPieces.isEmpty() ? null : replacementPieces.get(0);
        gbc.gridx = 0; gbc.gridy = 1;
        gbc.weightx = 1; gbc.anchor = GridBagConstraints.CENTER;
        gbc.fill = GridBagConstraints.NONE;
        if (firstAdditionalFurniture == null) {
            additionalPanel.add(panelHint(text("emptyReplacementHint",
                    "Select in the plan or in the furniture list the piece or pieces to use as replacement."), 2), gbc);
        } else if (replacementPieces.size() == 1) {
            JLabel label = new JLabel(firstAdditionalFurniture.getName(), pieceIcon(firstAdditionalFurniture, 112, 96),
                    JLabel.LEFT);
            label.setVerticalTextPosition(JLabel.BOTTOM);
            label.setHorizontalTextPosition(JLabel.CENTER);
            label.setHorizontalAlignment(JLabel.CENTER);
            label.setFont(new Font("SansSerif", Font.PLAIN, 18));
            bindPanelSelection(2, label);
            additionalPanel.add(label, gbc);
        } else {
            JLabel label = new JLabel(java.text.MessageFormat.format(
                    text("replacementGroupLabel", "Group of {0} pieces"), replacementPieces.size()),
                    multiPieceIcon(replacementPieces), JLabel.LEFT);
            label.setVerticalTextPosition(JLabel.BOTTOM);
            label.setHorizontalTextPosition(JLabel.CENTER);
            label.setFont(new Font("SansSerif", Font.PLAIN, 18));
            additionalPanel.add(label, gbc);
            StringBuilder names = new StringBuilder();
            for (int k = 0; k < replacementPieces.size() && k < 5; k++) {
                names.append(k > 0 ? ", " : "").append(replacementPieces.get(k).getName());
            }
            if (replacementPieces.size() > 5) {
                names.append(", ...");
            }
            JLabel list = panelHint(names.toString(), 2);
            bindPanelSelection(2, label);
            gbc.gridy = 2;
            additionalPanel.add(list, gbc);
        }

        additionalPanel.revalidate();
        additionalPanel.repaint();
        updateLightPowerOption();
        updateReplacementConflicts();
        checkSwapAvailability();
    }

    private void checkSwapAvailability() {
        boolean hasReplacement = replacementPieces != null && !replacementPieces.isEmpty();
        if (swapButton != null) {
            swapButton.setEnabled(hasReplacement);
        }
        if (clearReplacementButton != null) {
            clearReplacementButton.setEnabled(hasReplacement);
        }
    }

    private void swapSelection() {
        if (replacementPieces == null || replacementPieces.isEmpty()) {
            return;
        }
        List<HomePieceOfFurniture> oldOrigin = initialSelectedFurniture != null
                ? new ArrayList<>(initialSelectedFurniture) : new ArrayList<>();

        initialSelectedFurniture = new LinkedHashSet<>(replacementPieces);
        replacementPieces = oldOrigin;
        selectedOriginPiece = null;
        if (replacementPieces.isEmpty()) {
            selectPanel(2);
        }

        for (int property = 0; property < copySettings.length; property++) {
            copySettings[property] = new ReplacerSettings.MaterialCopy();
            searchSettings[property] = new ReplacerSettings.MaterialSearch();
        }
        replacementChanged();
        replacing = true;
        try {
            getHome().setSelectedItems(filterInHome(oldOrigin));
        } finally {
            replacing = false;
        }

        updateFirstPanel();
        updateSecondPanel();
        refreshPlanPreview();
    }

    /** Captures level for a piece and all its nested group descendants in pre-order. */
    private static Map<HomePieceOfFurniture, Level> captureTreeLevels(HomePieceOfFurniture root) {
        Map<HomePieceOfFurniture, Level> levels = new LinkedHashMap<>();
        collectTreeLevels(root, levels);
        return levels;
    }

    private static void collectTreeLevels(HomePieceOfFurniture piece, Map<HomePieceOfFurniture, Level> levels) {
        if (piece == null) {
            return;
        }
        levels.put(piece, piece.getLevel());
        if (piece instanceof HomeFurnitureGroup) {
            for (HomePieceOfFurniture child : ((HomeFurnitureGroup) piece).getFurniture()) {
                collectTreeLevels(child, levels);
            }
        }
    }

    private static void restoreTreeLevels(Map<HomePieceOfFurniture, Level> levels) {
        for (Map.Entry<HomePieceOfFurniture, Level> entry : levels.entrySet()) {
            Level level = entry.getValue();
            if (level != null) {
                entry.getKey().setLevel(level);
            }
        }
    }

    private static void applyReplacementBatch(Home home, List<HomePieceOfFurniture> addedItems,
            List<Map<HomePieceOfFurniture, Level>> addedLevels, List<HomePieceOfFurniture> deletedItems) {
        for (int i = 0; i < addedItems.size(); i++) {
            HomePieceOfFurniture piece = addedItems.get(i);
            home.addPieceOfFurniture(piece);
            restoreTreeLevels(addedLevels.get(i));
        }
        for (HomePieceOfFurniture piece : deletedItems) {
            home.deletePieceOfFurniture(piece);
        }
        home.setSelectedItems(addedItems);
    }

    /** Executes furniture replacement as a single batch and posts a reversible UndoableEdit. */
    private int replaceWithUndo(final ResourceBundle resource) {
        final Home home = boundHome != null ? boundHome : getHome();
        int replacedCount = replaceFurn(resource);
        if (replacedCount <= 0 || home == null) {
            return 0;
        }
        final List<HomePieceOfFurniture> addedItems = new ArrayList<>(toBeAdded);
        final List<HomePieceOfFurniture> deletedItems = removeNestedItems(toBeDeleted);
        if (addedItems.isEmpty() && deletedItems.isEmpty()) {
            return 0;
        }
        final List<Map<HomePieceOfFurniture, Level>> addedLevels = new ArrayList<>(addedItems.size());
        for (HomePieceOfFurniture piece : addedItems) {
            addedLevels.add(captureTreeLevels(piece));
        }
        final List<Map<HomePieceOfFurniture, Level>> deletedLevels = new ArrayList<>(deletedItems.size());
        final List<Integer> deletedIndices = new ArrayList<>(deletedItems.size());
        List<HomePieceOfFurniture> currentFurniture = home.getFurniture();
        for (HomePieceOfFurniture piece : deletedItems) {
            deletedLevels.add(captureTreeLevels(piece));
            deletedIndices.add(currentFurniture.indexOf(piece));
        }
        replacing = true;
        try {
            applyReplacementBatch(home, addedItems, addedLevels, deletedItems);
        } finally {
            replacing = false;
        }
        getUndoableEditSupport().postEdit(new AbstractUndoableEdit() {
            private static final long serialVersionUID = 1L;

            @Override
            public void undo() throws javax.swing.undo.CannotUndoException {
                super.undo();
                for (HomePieceOfFurniture piece : addedItems) {
                    home.deletePieceOfFurniture(piece);
                }
                for (int i = 0; i < deletedItems.size(); i++) {
                    HomePieceOfFurniture piece = deletedItems.get(i);
                    int idx = deletedIndices.get(i);
                    if (idx >= 0 && idx <= home.getFurniture().size()) {
                        home.addPieceOfFurniture(piece, idx);
                    } else {
                        home.addPieceOfFurniture(piece);
                    }
                    restoreTreeLevels(deletedLevels.get(i));
                }
                home.setSelectedItems(deletedItems);
            }

            @Override
            public void redo() throws javax.swing.undo.CannotRedoException {
                super.redo();
                applyReplacementBatch(home, addedItems, addedLevels, deletedItems);
            }

            @Override public String getPresentationName() { return resource.getString("undoReplace"); }
        });
        return replacedCount;
    }

    private int replaceFurn(ResourceBundle resource) {
        appearanceWarningShown = false;
        toBeDeleted = new ArrayList<>();
        toBeAdded = new ArrayList<>();

        HomePieceOfFurniture replacement = getReplacement();
        if (replacement == null) {
            return 0;
        }
        boolean randomValues = copyRandom != null && copyRandom.isSelected() && randomRanges.anyEnabled();
        activeRandomRanges = randomValues ? randomRanges : null;
        random = new Random();

        replacing = true;
        try {
            return replaceMatchingFurn(replacement, resource);
        } finally {
            replacing = false;
        }
    }

    private int replaceMatchingFurn(HomePieceOfFurniture replacement, ResourceBundle resource) {
        List<HomePieceOfFurniture> allFurniture = new ArrayList<>(getHome().getFurniture());
        Set<HomePieceOfFurniture> toBeReplaced = getAllMatchingFurn(allFurniture, initialSelectedFurniture,
                getSelectedMatchProperties());

        toBeReplaced.removeAll(replacementPieces);
        int replacedCount = 0;
        for (HomePieceOfFurniture homePiece : allFurniture) {
            if (isReplacementSource(homePiece)) {
                continue;
            }
            HomePieceOfFurniture rebuiltGroup = null;
            if (toBeReplaced.contains(homePiece)) {
                if (isReplacedInside(homePiece)) {
                    rebuiltGroup = replaceInside(replacement, (HomeFurnitureGroup) homePiece, resource);
                } else {
                    replaceWhole(replacement, homePiece, false, resource);
                }
                replacedCount++;
            } else if (homePiece instanceof HomeFurnitureGroup) {
                rebuiltGroup = replaceMatchesInside(replacement, (HomeFurnitureGroup) homePiece, toBeReplaced, resource);
                if (rebuiltGroup != null) {
                    replacedCount++;
                }
            }
            if (rebuiltGroup != null && preview == null) {
                queueRebuiltGroup((HomeFurnitureGroup) rebuiltGroup, (HomeFurnitureGroup) homePiece);
            }
        }
        return replacedCount;
    }

    private boolean isReplacedInside(HomePieceOfFurniture piece) {
        return piece instanceof HomeFurnitureGroup && replaceGroupFurn.isSelected();
    }

    private HomePieceOfFurniture replaceWhole(HomePieceOfFurniture replacement, HomePieceOfFurniture piece,
            boolean inGroup, ResourceBundle resource) {
        if (preview != null) {
            preview.add(piece);
            return piece;
        }
        return createNewFurn(replacement, piece, inGroup, resource);
    }

    private HomePieceOfFurniture keep(HomePieceOfFurniture piece) {
        if (preview != null) {
            return piece;
        }
        HomePieceOfFurniture copy = piece.clone();
        updateSizeInPlan(copy);
        return copy;
    }

    private HomePieceOfFurniture rebuild(HomeFurnitureGroup group, List<HomePieceOfFurniture> pieces) {
        if (preview != null) {
            return group;
        }
        List<Map<HomePieceOfFurniture, Level>> childLevels = new ArrayList<>(pieces.size());
        for (HomePieceOfFurniture child : pieces) {
            updateSizeInPlan(child);
            childLevels.add(captureTreeLevels(child));
        }
        HomeFurnitureGroup rebuilt = new HomeFurnitureGroup(pieces, group.getAngle(), group.isModelMirrored(), group.getName());
        rebuilt.setLevel(group.getLevel());
        rebuilt.setVisible(group.isVisible());
        rebuilt.setMovable(group.isMovable());
        for (Map<HomePieceOfFurniture, Level> levelMap : childLevels) {
            restoreTreeLevels(levelMap);
        }
        updateSizeInPlan(rebuilt);
        return rebuilt;
    }

    private HomePieceOfFurniture replaceInside(HomePieceOfFurniture replacement, HomeFurnitureGroup group,
            ResourceBundle resource) {
        return replaceLevel(replacement, group, 1, groupLevels.lastDepth(maxGroupDepth(group)), resource);
    }

    private HomePieceOfFurniture replaceLevel(HomePieceOfFurniture replacement, HomeFurnitureGroup group,
            int depth, int lastDepth, ResourceBundle resource) {
        List<HomePieceOfFurniture> pieces = new ArrayList<>();
        for (HomePieceOfFurniture child : group.getFurniture()) {
            if (isReplacementSource(child)) {
                pieces.add(keep(child));
            } else if (!(child instanceof HomeFurnitureGroup)) {
                pieces.add(replaceWhole(replacement, child, true, resource));
            } else if (depth < lastDepth) {
                pieces.add(replaceLevel(replacement, (HomeFurnitureGroup) child, depth + 1, lastDepth, resource));
            } else {
                boolean whole = groupLevels.wholeAtLimit && Collections.disjoint(
                        ((HomeFurnitureGroup) child).getAllFurniture(), replacementPieces);
                pieces.add(whole ? replaceWhole(replacement, child, true, resource) : keep(child));
            }
        }
        return rebuild(group, pieces);
    }

    private HomePieceOfFurniture replaceMatchesInside(HomePieceOfFurniture replacement, HomeFurnitureGroup group,
            Set<HomePieceOfFurniture> toBeReplaced, ResourceBundle resource) {
        List<HomePieceOfFurniture> pieces = new ArrayList<>();
        boolean changed = false;
        for (HomePieceOfFurniture child : group.getFurniture()) {
            HomePieceOfFurniture newPiece = null;
            if (!isReplacementSource(child)) {
                if (toBeReplaced.contains(child)) {
                    newPiece = isReplacedInside(child)
                            ? replaceInside(replacement, (HomeFurnitureGroup) child, resource)
                            : replaceWhole(replacement, child, true, resource);
                } else if (child instanceof HomeFurnitureGroup) {
                    newPiece = replaceMatchesInside(replacement, (HomeFurnitureGroup) child, toBeReplaced, resource);
                }
            }
            changed |= newPiece != null;
            pieces.add(newPiece != null ? newPiece : keep(child));
        }
        return changed ? rebuild(group, pieces) : null;
    }

    private List<String> getSelectedMatchProperties() {
        List<String> matchProperties = new ArrayList<>();
        if (propertyChecks != null) {
            for (int i = 0; i < propertyChecks.size() && i < SEARCH_PROPERTY_KEYS.length; i++) {
                if (propertyChecks.get(i).isSelected()) {
                    matchProperties.add(SEARCH_PROPERTY_KEYS[i]);
                }
            }
        }
        if (matchProperties.isEmpty()) {
            matchProperties.add("model");
        }
        return matchProperties;
    }

    private void queueRebuiltGroup(HomeFurnitureGroup newGroup, HomeFurnitureGroup originalGroup) {
        toBeAdded.add(newGroup);
        toBeDeleted.add(originalGroup);
    }

    private List<HomePieceOfFurniture> removeNestedItems(List<? extends Selectable> items) {
        Set<HomePieceOfFurniture> itemSet = new LinkedHashSet<>();
        for (Selectable item : items) {
            if (item instanceof HomePieceOfFurniture) {
                itemSet.add((HomePieceOfFurniture) item);
            }
        }
        Map<HomePieceOfFurniture, HomeFurnitureGroup> parents = new HashMap<>();
        fillParents(getHome().getFurniture(), null, parents);
        List<HomePieceOfFurniture> result = new ArrayList<>();
        for (HomePieceOfFurniture piece : itemSet) {
            boolean ancestorDeleted = false;
            for (HomeFurnitureGroup parent = parents.get(piece); parent != null; parent = parents.get(parent)) {
                if (itemSet.contains(parent)) {
                    ancestorDeleted = true;
                    break;
                }
            }
            if (!ancestorDeleted) {
                result.add(piece);
            }
        }
        return result;
    }

    private static void fillParents(List<HomePieceOfFurniture> furniture, HomeFurnitureGroup parent,
                                    Map<HomePieceOfFurniture, HomeFurnitureGroup> parents) {
        for (HomePieceOfFurniture piece : furniture) {
            if (parent != null) {
                parents.put(piece, parent);
            }
            if (piece instanceof HomeFurnitureGroup) {
                fillParents(((HomeFurnitureGroup) piece).getFurniture(), (HomeFurnitureGroup) piece, parents);
            }
        }
    }

    /** Clones replacement piece and transfers configured properties from homePiece. */
    private HomePieceOfFurniture createNewFurn(HomePieceOfFurniture replacement, HomePieceOfFurniture homePiece, boolean delete, ResourceBundle resource) {
        if (activeRandomRanges != null && activeRandomRanges.pickOnePiece) {
            List<HomePieceOfFurniture> objects = replacementObjects();
            if (objects.size() > 1) {
                replacement = objects.get(randomIndex(activeRandomRanges.pieceWeights, objects.size()));
            }
        }

        HomePieceOfFurniture newFurniture = replacement.clone();
        newFurniture.setVisible(homePiece.isVisible());

        if (copyX.isSelected()) {
            newFurniture.setX(homePiece.getX());
        }
        if (copyY.isSelected()) {
            newFurniture.setY(homePiece.getY());
        }
        if (copyElevation.isSelected()) {
            newFurniture.setElevation(homePiece.getElevation());
        }
        if (copyAngle.isSelected()) {
            newFurniture.setAngle(homePiece.getAngle());
        }

        if (copyPitch.isSelected() && newFurniture.isHorizontallyRotatable()) {
            newFurniture.setPitch(homePiece.getPitch());
        }
        if (copyRoll.isSelected() && newFurniture.isHorizontallyRotatable()) {
            newFurniture.setRoll(homePiece.getRoll());
        }
        if (copyLevel.isSelected()) {
            newFurniture.setLevel(homePiece.getLevel());
        } else if (newFurniture.getLevel() == null && getHome() != null) {
            newFurniture.setLevel(getHome().getSelectedLevel());
        }
        if (copyMirrored.isSelected()) {
            newFurniture.setModelMirrored(homePiece.isModelMirrored());
        }
        if (copyMovable.isSelected()) {
            newFurniture.setMovable(homePiece.isMovable());
        }

        copySizes(homePiece, newFurniture);
        updateSizeInPlan(newFurniture);
        placeLikeOriginal(homePiece, newFurniture);
        float alignedX = newFurniture.getX(), alignedY = newFurniture.getY();

        HomeLight originalLight = copyLightPower.isSelected() ? firstLight(homePiece) : null;
        if (originalLight != null) {
            setLightPower(newFurniture, originalLight.getPower());
        }

        HomeMaterial[] originalMaterials = null;
        if (copyAppearance.isSelected() || copyShininess.isSelected()) {
            originalMaterials = homePiece.getModelMaterials();
            if (originalMaterials == null || originalMaterials.length == 0) {
                originalMaterials = MaterialUtils.defaultMaterials(homePiece);
            }
        }
        boolean appearanceFound = !copyAppearance.isSelected()
                || copyProperty(MaterialUtils.APPEARANCE, homePiece, newFurniture, originalMaterials);
        if (copyShininess.isSelected()) {
            copyProperty(MaterialUtils.SHININESS, homePiece, newFurniture, originalMaterials);
        }

        if (!appearanceFound && !appearanceWarningShown) {
            JOptionPane.showMessageDialog(replaceFrame,
                    text("notFoundMaterialsMessage", "The source object has no color or texture to transfer."),
                    text("notFoundMaterialsTitle", "Materials not available"),
                    JOptionPane.WARNING_MESSAGE);
            appearanceWarningShown = true;
        }

        if (activeRandomRanges != null) {
            applyRandomValues(newFurniture, activeRandomRanges);
            float dx = newFurniture.getX() - alignedX, dy = newFurniture.getY() - alignedY;
            updateSizeInPlan(newFurniture);
            placeLikeOriginal(homePiece, newFurniture);
            newFurniture.setX(newFurniture.getX() + dx);
            newFurniture.setY(newFurniture.getY() + dy);
        }

        if (!delete) {
            toBeAdded.add(newFurniture);
        }
        toBeDeleted.add(homePiece);
        return newFurniture;
    }

    /**
     * Aligns the replacement piece to the original piece's 2D plan anchor or wall opening.
     * Takes into account rotation angles, anchor alignment offsets (-1, 0, 1), and door/window wall depth.
     */
    private void placeLikeOriginal(HomePieceOfFurniture original, HomePieceOfFurniture piece) {
        boolean x = copyX.isSelected();
        boolean y = copyY.isSelected();
        boolean inWall = x && y && original instanceof HomeDoorOrWindow && piece instanceof HomeDoorOrWindow
                && ((HomeDoorOrWindow) original).isBoundToWall();
        if (!inWall && alignment.column == 0 && alignment.row == 0 || !x && !y) {
            return;
        }

        float originalX = alignment.column * (inWall ? original.getWidth() : widthInPlan(original)) / 2;
        float originalY = alignment.row * (inWall ? original.getDepth() : depthInPlan(original)) / 2;
        float pieceX = alignment.column * (inWall ? piece.getWidth() : widthInPlan(piece)) / 2;
        float pieceY = alignment.row * (inWall ? piece.getDepth() : depthInPlan(piece)) / 2;
        if (inWall) {
            HomeDoorOrWindow door = (HomeDoorOrWindow) original;
            HomeDoorOrWindow newDoor = (HomeDoorOrWindow) piece;
            newDoor.setAngle(door.getAngle());
            if (newDoor.isResizable() && newDoor.isWidthDepthDeformable()
                    && door.getWallThickness() > 1e-5f && newDoor.getWallThickness() > 1e-5f) {
                newDoor.setDepth(safeDim(door.getDepth() * door.getWallThickness() / newDoor.getWallThickness(), newDoor.getDepth()));
            }

            originalY = -door.getDepth() / 2 + door.getDepth() * door.getWallDistance();
            pieceY = -newDoor.getDepth() / 2 + newDoor.getDepth() * newDoor.getWallDistance();
        }
        double originalCos = Math.cos(original.getAngle()), originalSin = Math.sin(original.getAngle());
        double pieceCos = Math.cos(piece.getAngle()), pieceSin = Math.sin(piece.getAngle());
        double pointX = original.getX() + originalX * originalCos - originalY * originalSin;
        double pointY = original.getY() + originalX * originalSin + originalY * originalCos;
        if (x) {
            piece.setX((float) (pointX - (pieceX * pieceCos - pieceY * pieceSin)));
        }
        if (y) {
            piece.setY((float) (pointY - (pieceX * pieceSin + pieceY * pieceCos)));
        }
        if (inWall) {
            ((HomeDoorOrWindow) piece).setBoundToWall(true);
        }
    }

    private static float safeDim(float value, float fallback) {
        return value > 0.01f && !Float.isNaN(value) && !Float.isInfinite(value) ? value : Math.max(0.01f, fallback);
    }

    /** Copies checked dimensions and applies proportional scaling when enabled. */
    private void copySizes(HomePieceOfFurniture homePiece, HomePieceOfFurniture newFurniture) {
        boolean w = copyWidth.isSelected(), d = copyDepth.isSelected(), h = copyHeight.isSelected();
        if (!(w || d || h) || !newFurniture.isResizable()) {
            return;
        }
        float nw = newFurniture.getWidth(), nd = newFurniture.getDepth(), nh = newFurniture.getHeight();
        if (canBeDeformed(newFurniture)) {
            float[] ratios = {
                w && nw > 1e-5f ? homePiece.getWidth() / nw : 0f,
                d && nd > 1e-5f ? homePiece.getDepth() / nd : 0f,
                h && nh > 1e-5f ? homePiece.getHeight() / nh : 0f};
            int count = checkedSizes();

            int base = proportionalSizes.base;
            float ratio = count == 2 && base >= 0 && ratios[base] > 0 ? ratios[base]
                    : count > 0 ? (ratios[0] + ratios[1] + ratios[2]) / count : 0f;
            boolean proportional = proportionalSizes.enabled && count < 3
                    && ratio > 1e-4f && !Float.isInfinite(ratio) && !Float.isNaN(ratio);
            if (w || proportional) {
                newFurniture.setWidth(safeDim(w ? homePiece.getWidth() : nw * ratio, nw));
            }
            if (d || proportional) {
                newFurniture.setDepth(safeDim(d ? homePiece.getDepth() : nd * ratio, nd));
            }
            if (h || proportional) {
                newFurniture.setHeight(safeDim(h ? homePiece.getHeight() : nh * ratio, nh));
            }
            return;
        }
        float factor = w ? (nw > 1e-5f ? homePiece.getWidth() / nw : 0f)
                : d ? (nd > 1e-5f ? homePiece.getDepth() / nd : 0f)
                : (nh > 1e-5f ? homePiece.getHeight() / nh : 0f);
        if (factor > 1e-4f && !Float.isInfinite(factor) && !Float.isNaN(factor)) {
            newFurniture.scale(factor);
        }
    }

    /** Effective 2D plan dimension of a piece. */
    private static float inPlan(HomePieceOfFurniture piece, float planDim, float modelDim) {
        float v = piece.isHorizontallyRotated() ? planDim : modelDim;
        return v > 0f ? v : modelDim;
    }

    private static float widthInPlan(HomePieceOfFurniture p) { return p == null ? 0f : inPlan(p, p.getWidthInPlan(), p.getWidth()); }
    private static float depthInPlan(HomePieceOfFurniture p) { return p == null ? 0f : inPlan(p, p.getDepthInPlan(), p.getDepth()); }
    private static float heightInPlan(HomePieceOfFurniture p) { return p == null ? 0f : inPlan(p, p.getHeightInPlan(), p.getHeight()); }

    private static float[] rotatedBoxSizeInPlan(HomePieceOfFurniture piece) {
        double w = piece.getWidth(), d = piece.getDepth(), h = piece.getHeight();
        double cp = Math.cos(piece.getPitch()), sp = Math.sin(piece.getPitch());
        double cr = Math.cos(piece.getRoll()), sr = Math.sin(piece.getRoll());
        double maxX = 0, maxY = 0, maxZ = 0;
        for (int sx : new int[]{-1, 1}) {
            for (int sy : new int[]{-1, 1}) {
                for (int sz : new int[]{-1, 1}) {
                    double x0 = sx * w / 2, y0 = sy * h / 2, z0 = sz * d / 2;
                    double x1 = x0 * cr - y0 * sr;
                    double y1 = x0 * sr + y0 * cr;
                    double y2 = y1 * cp - z0 * sp;
                    double z2 = y1 * sp + z0 * cp;
                    maxX = Math.max(maxX, Math.abs(x1));
                    maxY = Math.max(maxY, Math.abs(y2));
                    maxZ = Math.max(maxZ, Math.abs(z2));
                }
            }
        }
        return new float[]{
            safeDim((float) (2 * maxX), piece.getWidth()),
            safeDim((float) (2 * maxZ), piece.getDepth()),
            safeDim((float) (2 * maxY), piece.getHeight())
        };
    }

    /** Updates widthInPlan, depthInPlan, and heightInPlan according to horizontal pitch/roll rotation. */
    private void updateSizeInPlan(HomePieceOfFurniture piece) {
        if (piece == null) {
            return;
        }
        if (piece instanceof HomeFurnitureGroup) {
            for (HomePieceOfFurniture child : ((HomeFurnitureGroup) piece).getFurniture()) {
                updateSizeInPlan(child);
            }
            return;
        }
        float[] size = null;
        if (piece.isHorizontallyRotated()
                && getHomeController() != null
                && getHomeController().getPlanController() != null
                && getHomeController().getPlanController().getView() != null) {
            try {
                size = getHomeController().getPlanController().getView().getPieceOfFurnitureSizeInPlan(piece);
            } catch (RuntimeException ignored) {
            }
        }
        if (size != null && size[0] > 0f && size[1] > 0f && size[2] > 0f) {
            piece.setWidthInPlan(size[0]);
            piece.setDepthInPlan(size[1]);
            piece.setHeightInPlan(size[2]);
        } else if (piece.isHorizontallyRotated()) {
            float[] fallback = rotatedBoxSizeInPlan(piece);
            piece.setWidthInPlan(fallback[0]);
            piece.setDepthInPlan(fallback[1]);
            piece.setHeightInPlan(fallback[2]);
        } else {
            piece.setWidthInPlan(piece.getWidth());
            piece.setDepthInPlan(piece.getDepth());
            piece.setHeightInPlan(piece.getHeight());
        }
    }

    static boolean canBeDeformed(HomePieceOfFurniture piece) {
        if (!piece.isDeformable()) {
            return false;
        }
        if (piece instanceof HomeFurnitureGroup) {
            for (HomePieceOfFurniture child : ((HomeFurnitureGroup) piece).getAllFurniture()) {
                double relative = Math.toDegrees(child.getAngle() - piece.getAngle());
                double rest = ((relative % 90) + 90) % 90;
                if ((rest > 0.01 && rest < 89.99) || Math.abs(child.getPitch()) > 1e-4 || Math.abs(child.getRoll()) > 1e-4) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Resolves the primary appearance or shininess value representing the entire piece,
     * falling back to the first non-null component material if whole-piece properties are unset.
     */
    private static Object wholeValue(int property, HomePieceOfFurniture homePiece, HomeMaterial[] materials) {
        if (property == MaterialUtils.APPEARANCE) {
            Object value = MaterialUtils.appearance(homePiece.getColor(), homePiece.getTexture());
            if (value == null && materials != null) {
                for (HomeMaterial mat : materials) {
                    if (mat != null && (mat.getColor() != null || mat.getTexture() != null)) {
                        return MaterialUtils.appearance(mat.getColor(), mat.getTexture());
                    }
                }
            }
            return value;
        }
        if (homePiece.getShininess() != null) {
            return homePiece.getShininess();
        }
        return materials != null && materials.length > 0 && materials[0] != null
                ? materials[0].getShininess() : null;
    }

    /** Extracts material property specified by source selector from homePiece. */
    private static Object sourceValue(ReplacerSettings.Source source, int property, HomePieceOfFurniture homePiece,
            HomeMaterial[] originalMaterials) {
        if (source == null || source.whole || source.materials.isEmpty()) {
            return wholeValue(property, homePiece, originalMaterials);
        }
        for (boolean strict : new boolean[]{true, false}) {
            for (String material : source.materials) {
                Object value = MaterialUtils.valueOf(homePiece, material, property, strict);
                if (value != null) {
                    return value;
                }
            }
        }
        return null;
    }

    /** Copies appearance or shininess properties according to configured mapping. */
    private boolean copyProperty(int property, HomePieceOfFurniture homePiece, HomePieceOfFurniture newFurniture,
            HomeMaterial[] originalMaterials) {
        ReplacerSettings.MaterialCopy settings = copySettings[property];
        if (settings.toWhole) {
            Object value = sourceValue(settings.from, property, homePiece, originalMaterials);
            if (value == null) {
                return false;
            }
            MaterialUtils.setPieceValue(newFurniture, value, property);
            return true;
        }

        Map<String, Object> values = new LinkedHashMap<>();
        for (Map.Entry<String, ReplacerSettings.Source> entry : settings.toMaterials.entrySet()) {
            Object value = sourceValue(entry.getValue(), property, homePiece, originalMaterials);
            if (value != null) {
                values.put(entry.getKey(), value);
            }
        }
        return !values.isEmpty() && MaterialUtils.applyToMaterials(newFurniture, values, property) > 0;
    }

    /** Applies enabled random offsets, scales, angles, mirroring, and appearances. */
    private void applyRandomValues(HomePieceOfFurniture piece, ReplacerSettings.RandomRanges r) {
        double sharedFactor = Double.NaN;
        if (r.keepProportions || !canBeDeformed(piece)) {
            for (String size : new String[]{"width", "depth", "height"}) {
                int i = r.indexOf(size);
                if (r.enabled[i] && r.modes[i] == ReplacerSettings.RandomRanges.MODE_PERCENT) {
                    sharedFactor = randomBetween(r.min[i], r.max[i]) / 100;
                    break;
                }
            }
        }
        for (int i = 0; i < ReplacerSettings.RandomRanges.PROPERTIES.length; i++) {
            String property = ReplacerSettings.RandomRanges.PROPERTIES[i];
            if (!r.enabled[i]) {
                continue;
            }
            if (property.equals("shininess")) {
                applyRandomShininess(piece, r, i);
                continue;
            }
            double value = propertyValue(piece, property);
            if (Double.isNaN(value)) {
                continue;
            }
            boolean isSize = property.equals("width") || property.equals("depth") || property.equals("height");
            if (isSize && r.modes[i] == ReplacerSettings.RandomRanges.MODE_PERCENT && !Double.isNaN(sharedFactor)) {
                value *= sharedFactor;
            } else {
                value = drawn(value, r.modes[i], r.min[i], r.max[i]);
            }
            setRandomProperty(piece, property, value);
        }
        if (r.look.enabled) {
            if (r.look.fromList) {
                r.look.ensureDefaultChoices(defaultRandomLookChoices(r.look.target));
            }
            applyRandomLook(piece, r.look);
        }
        if (r.mirror && random.nextDouble() * 100 < r.mirrorProbability) {
            piece.setModelMirrored(!piece.isModelMirrored());
        }
    }

    private double drawn(double base, int mode, double min, double max) {
        return byMode(base, mode, randomBetween(min, max));
    }

    private static double byMode(double base, int mode, double drawn) {
        switch (mode) {
            case ReplacerSettings.RandomRanges.MODE_VALUE:
                return drawn;
            case ReplacerSettings.RandomRanges.MODE_ADD:
                return base + drawn;
            default:
                return base * drawn / 100;
        }
    }

    private void applyRandomShininess(HomePieceOfFurniture piece, ReplacerSettings.RandomRanges r, int i) {
        ReplacerSettings.Source target = r.shininessTarget;
        if (target.whole || target.materials.isEmpty()) {
            piece.setShininess(randomShininess(currentWholeValue(piece, MaterialUtils.SHININESS), r, i));
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (String material : target.materials) {
            values.put(material, randomShininess(MaterialUtils.valueOf(piece, material, MaterialUtils.SHININESS), r, i));
        }
        MaterialUtils.applyToMaterials(piece, values, MaterialUtils.SHININESS);
    }

    private Float randomShininess(Object base, ReplacerSettings.RandomRanges r, int i) {
        double percent = base instanceof Float ? (Float) base * 100 : 0;
        return (float) (Math.max(0, Math.min(100, drawn(percent, r.modes[i], r.min[i], r.max[i]))) / 100);
    }

    private static Object currentWholeValue(HomePieceOfFurniture piece, int property) {
        for (HomePieceOfFurniture leaf : MaterialUtils.leaves(piece)) {
            Object value = MaterialUtils.valueOf(leaf, MaterialUtils.WHOLE_PIECE, property);
            HomeMaterial[] defaults = MaterialUtils.defaultMaterials(leaf);
            for (int m = 0; value == null && defaults != null && m < defaults.length; m++) {
                if (defaults[m] != null && defaults[m].getName() != null) {
                    value = MaterialUtils.valueOf(leaf, defaults[m].getName(), property);
                }
            }
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private void applyRandomLook(HomePieceOfFurniture piece, ReplacerSettings.RandomLook look) {
        boolean list = look.fromList && !look.choices.isEmpty();
        if (!list && !look.vary) {
            return;
        }
        ReplacerSettings.Source target = look.target;
        if (target.whole || target.materials.isEmpty()) {
            if (list) {
                MaterialUtils.setPieceValue(piece, drawnLook(look), MaterialUtils.APPEARANCE);
                return;
            }
            double[] draws = componentDraws(look);
            if (piece.getTexture() != null) {
                return;
            }
            if (piece.getColor() != null) {
                piece.setColor(variedColor(piece.getColor(), look, draws));
                return;
            }

            for (HomePieceOfFurniture leaf : MaterialUtils.leaves(piece)) {
                if (leaf.getTexture() != null) {
                    continue;
                }
                if (leaf.getColor() != null) {
                    leaf.setColor(variedColor(leaf.getColor(), look, draws));
                    continue;
                }
                HomeMaterial[] defaults = MaterialUtils.defaultMaterials(leaf);
                Map<String, Object> values = new LinkedHashMap<>();
                for (int m = 0; defaults != null && m < defaults.length; m++) {
                    if (defaults[m] != null && defaults[m].getName() != null) {
                        Object color = MaterialUtils.valueOf(leaf, defaults[m].getName(), MaterialUtils.APPEARANCE);
                        if (color instanceof Integer) {
                            values.put(defaults[m].getName(), variedColor((Integer) color, look, draws));
                        }
                    }
                }
                if (!values.isEmpty()) {
                    MaterialUtils.applyToMaterials(leaf, values, MaterialUtils.APPEARANCE);
                }
            }
            return;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        for (String material : target.materials) {
            if (list) {
                values.put(material, drawnLook(look));
            } else {
                Object color = MaterialUtils.valueOf(piece, material, MaterialUtils.APPEARANCE);
                double[] draws = componentDraws(look);
                if (color instanceof Integer) {
                    values.put(material, variedColor((Integer) color, look, draws));
                }
            }
        }
        if (!values.isEmpty()) {
            MaterialUtils.applyToMaterials(piece, values, MaterialUtils.APPEARANCE);
        }
    }

    private Object drawnLook(ReplacerSettings.RandomLook look) {
        int index = randomIndex(look.weights, look.choices.size());
        Object value = look.choices.get(index);
        return look.isVaried(index) ? variedColor((Integer) value, look, componentDraws(look)) : value;
    }

    private int randomIndex(List<Double> weights, int count) {
        double total = 0;
        for (int i = 0; i < count; i++) {
            total += weight(weights, i);
        }
        if (total <= 0) {
            return random.nextInt(count);
        }
        double drawn = random.nextDouble() * total;
        for (int i = 0; i < count; i++) {
            drawn -= weight(weights, i);
            if (drawn < 0) {
                return i;
            }
        }
        return count - 1;
    }

    private static double weight(List<Double> weights, int i) {
        return i < weights.size() ? Math.max(0, weights.get(i)) : 1;
    }

    private double[] componentDraws(ReplacerSettings.RandomLook look) {
        double[] draws = new double[3];
        for (int c = 0; c < 3; c++) {
            draws[c] = look.sameComponents && c > 0 ? draws[0]
                    : randomBetween(look.componentMin[c], look.componentMax[c]);
        }
        return draws;
    }

    private static int variedColor(int color, ReplacerSettings.RandomLook look, double[] draws) {
        int result = color & 0xFF000000;
        for (int c = 0; c < 3; c++) {
            int shift = 16 - 8 * c;
            double component = byMode((color >> shift) & 0xFF, look.componentModes[look.sameComponents ? 0 : c], draws[c]);
            result |= (int) Math.max(0, Math.min(255, Math.round(component))) << shift;
        }
        return result;
    }

    private static double propertyValue(HomePieceOfFurniture piece, String property) {
        switch (property) {
            case "x": return piece.getX();
            case "y": return piece.getY();
            case "width": return piece.getWidth();
            case "depth": return piece.getDepth();
            case "height": return piece.getHeight();
            case "z": return piece.getElevation();
            case "angle": return Math.toDegrees(piece.getAngle());
            case "pitch": return Math.toDegrees(piece.getPitch());
            case "roll": return Math.toDegrees(piece.getRoll());
            default:
                HomeLight light = firstLight(piece);
                return light != null ? light.getPower() * 100 : Double.NaN;
        }
    }

    private static void setRandomProperty(HomePieceOfFurniture piece, String property, double value) {
        float size = (float) Math.max(0.1, value);
        boolean deformable = canBeDeformed(piece);
        switch (property) {
            case "x": piece.setX((float) value); break;
            case "y": piece.setY((float) value); break;
            case "width":
                if (piece.isResizable()) {
                    if (deformable) piece.setWidth(size);
                    else if (piece.getWidth() > 1e-5f) piece.scale(size / piece.getWidth());
                }
                break;
            case "depth":
                if (piece.isResizable()) {
                    if (deformable) piece.setDepth(size);
                    else if (piece.getDepth() > 1e-5f) piece.scale(size / piece.getDepth());
                }
                break;
            case "height":
                if (piece.isResizable()) {
                    if (deformable) piece.setHeight(size);
                    else if (piece.getHeight() > 1e-5f) piece.scale(size / piece.getHeight());
                }
                break;
            case "z": piece.setElevation((float) Math.max(0, value)); break;
            case "angle": piece.setAngle((float) Math.toRadians(value)); break;
            case "pitch": if (piece.isHorizontallyRotatable()) piece.setPitch((float) Math.toRadians(value)); break;
            case "roll": if (piece.isHorizontallyRotatable()) piece.setRoll((float) Math.toRadians(value)); break;
            default:

                setLightPower(piece, (float) Math.max(0, Math.min(1, value / 100)));
                break;
        }
    }

    private static HomeLight firstLight(HomePieceOfFurniture piece) {
        for (HomePieceOfFurniture leaf : MaterialUtils.leaves(piece)) {
            if (leaf instanceof HomeLight) {
                return (HomeLight) leaf;
            }
        }
        return null;
    }

    private static void setLightPower(HomePieceOfFurniture piece, float power) {
        for (HomePieceOfFurniture leaf : MaterialUtils.leaves(piece)) {
            if (leaf instanceof HomeLight) {
                ((HomeLight) leaf).setPower(power);
            }
        }
    }

    private double randomBetween(double min, double max) {
        return max <= min ? min : min + random.nextDouble() * (max - min);
    }

    private void collectReplacedGroupElements(HomeFurnitureGroup group, Set<HomePieceOfFurniture> out) {
        collectReplacedGroupElements(group, 1, groupLevels.lastDepth(maxGroupDepth(group)), out);
    }

    private void collectReplacedGroupElements(HomeFurnitureGroup group, int depth, int lastDepth,
            Set<HomePieceOfFurniture> out) {
        for (HomePieceOfFurniture child : group.getFurniture()) {
            if (isReplacementSource(child)) {
                continue;
            }
            if (!(child instanceof HomeFurnitureGroup)) {
                out.add(child);
            } else if (depth < lastDepth) {
                collectReplacedGroupElements((HomeFurnitureGroup) child, depth + 1, lastDepth, out);
            } else if (groupLevels.wholeAtLimit
                    && Collections.disjoint(((HomeFurnitureGroup) child).getAllFurniture(), replacementPieces)) {
                out.add(child);
            }
        }
    }

    /** Finds all furniture in the home matching the initial selection based on search properties. */
    private Set<HomePieceOfFurniture> getAllMatchingFurn(Collection<HomePieceOfFurniture> allFurniture, Set<HomePieceOfFurniture> initialFurniture, List<String> matchProperties) {
        Set<HomePieceOfFurniture> result = new LinkedHashSet<>(initialFurniture);
        if (!replaceSelectedOnly.isSelected()) {
            Map<HomePieceOfFurniture, HomeFurnitureGroup> parentMap = new HashMap<>();
            fillParents(getHome().getFurniture(), null, parentMap);
            Set<HomePieceOfFurniture> sources = new LinkedHashSet<>();
            Set<HomePieceOfFurniture> groupModelSources = new HashSet<>();
            boolean matchGroupModels = replaceGroupFurn != null && replaceGroupFurn.isSelected()
                    && groupModelsCheck != null && groupModelsCheck.isSelected();
            for (HomePieceOfFurniture piece : initialFurniture) {
                if (matchGroupModels && piece instanceof HomeFurnitureGroup) {
                    Set<HomePieceOfFurniture> elements = new LinkedHashSet<>();
                    collectReplacedGroupElements((HomeFurnitureGroup) piece, elements);
                    sources.addAll(elements);
                    groupModelSources.addAll(elements);
                } else {
                    sources.add(piece);
                }
            }
            addMatchingFurn(allFurniture, result, initialFurniture, sources, groupModelSources,
                    matchProperties, parentMap, 0, Integer.MAX_VALUE);
        }
        return result;
    }

    /**
     * Recursively traverses candidate furniture collections (including subgroups) to identify matching pieces.
     */
    private void addMatchingFurn(Collection<HomePieceOfFurniture> furniture, Set<HomePieceOfFurniture> result,
            Set<HomePieceOfFurniture> initialFurniture, Set<HomePieceOfFurniture> sources,
            Set<HomePieceOfFurniture> groupModelSources, List<String> matchProperties,
            Map<HomePieceOfFurniture, HomeFurnitureGroup> parentMap, int depth, int lastDepth) {
        boolean insideGroupMode = replaceGroupFurn != null && replaceGroupFurn.isSelected();
        for (HomePieceOfFurniture piece : furniture) {
            if (initialFurniture.contains(piece)) {
                continue;
            }
            if (skipHiddenCheck != null && skipHiddenCheck.isSelected() && isHiddenOrLocked(piece)) {
                continue;
            }
            if (!(piece instanceof HomeFurnitureGroup)) {
                if (matchesAnySource(piece, sources, groupModelSources, matchProperties, parentMap)) {
                    result.add(piece);
                }
            } else {
                HomeFurnitureGroup group = (HomeFurnitureGroup) piece;
                int groupLastDepth = depth == 0 && insideGroupMode
                        ? groupLevels.lastDepth(maxGroupDepth(group)) : lastDepth;
                if (depth < groupLastDepth) {
                    if (matchesAnySource(group, sources, groupModelSources, matchProperties, parentMap)) {
                        result.add(group);
                    } else {
                        addMatchingFurn(group.getFurniture(), result, initialFurniture, sources,
                                groupModelSources, matchProperties, parentMap, depth + 1, groupLastDepth);
                    }
                } else if (!insideGroupMode || groupLevels.wholeAtLimit) {
                    if (matchesAnySource(group, sources, groupModelSources, matchProperties, parentMap)) {
                        result.add(group);
                    }
                }
            }
        }
    }

    private boolean matchesAnySource(HomePieceOfFurniture candidate, Set<HomePieceOfFurniture> sources,
            Set<HomePieceOfFurniture> groupModelSources, List<String> matchProperties,
            Map<HomePieceOfFurniture, HomeFurnitureGroup> parentMap) {
        for (HomePieceOfFurniture source : sources) {
            if (candidate != source && isMatching(candidate, source, matchProperties)) {
                if (!groupModelSources.contains(source) && layoutMatters() && replaceGroupFurn.isSelected()
                        && !(source instanceof HomeFurnitureGroup) && !(candidate instanceof HomeFurnitureGroup)) {
                    HomeFurnitureGroup srcParent = parentMap.get(source);
                    HomeFurnitureGroup candParent = parentMap.get(candidate);
                    if (srcParent != null && (candParent == null || !isGroupContentEqual(candParent, srcParent)
                            || !isGroupLayoutEqual(candParent, srcParent))) {
                        continue;
                    }
                }
                return true;
            }
        }
        return false;
    }

    private boolean layoutMatters() {
        return groupLayoutCheck != null && groupLayoutCheck.isSelected();
    }

    static boolean isGroupContentEqual(HomeFurnitureGroup group1, HomeFurnitureGroup group2) {
        List<HomePieceOfFurniture> list1 = MaterialUtils.leaves(group1);
        List<HomePieceOfFurniture> list2 = MaterialUtils.leaves(group2);
        if (list1.size() != list2.size()) {
            return false;
        }
        return getModelFrequencyMap(list1).equals(getModelFrequencyMap(list2));
    }

    private static Map<Object, Integer> getModelFrequencyMap(List<HomePieceOfFurniture> furnitureList) {
        Map<Object, Integer> frequencyMap = new HashMap<>();
        for (HomePieceOfFurniture piece : furnitureList) {
            frequencyMap.merge(MaterialUtils.modelKey(piece), 1, Integer::sum);
        }
        return frequencyMap;
    }

    private boolean isSameModel(HomePieceOfFurniture candidate, HomePieceOfFurniture source) {
        boolean candidateIsGroup = candidate instanceof HomeFurnitureGroup;
        boolean sourceIsGroup = source instanceof HomeFurnitureGroup;
        if (candidateIsGroup || sourceIsGroup) {
            return candidateIsGroup && sourceIsGroup
                    && isGroupContentEqual((HomeFurnitureGroup) candidate, (HomeFurnitureGroup) source)
                    && (!layoutMatters()
                        || isGroupLayoutEqual((HomeFurnitureGroup) candidate, (HomeFurnitureGroup) source));
        }
        return MaterialUtils.modelKey(candidate).equals(MaterialUtils.modelKey(source));
    }

    static boolean isGroupLayoutEqual(HomeFurnitureGroup group1, HomeFurnitureGroup group2) {
        if (group1.isModelMirrored() != group2.isModelMirrored()) {
            return false;
        }
        List<double[]> layout1 = getGroupLayout(group1);
        List<double[]> layout2 = getGroupLayout(group2);
        List<HomePieceOfFurniture> leaves1 = MaterialUtils.leaves(group1);
        List<HomePieceOfFurniture> leaves2 = MaterialUtils.leaves(group2);
        if (layout1.size() != layout2.size()) {
            return false;
        }
        boolean[] used = new boolean[layout2.size()];
        for (int i = 0; i < layout1.size(); i++) {
            boolean found = false;
            for (int j = 0; j < layout2.size() && !found; j++) {
                if (!used[j] && MaterialUtils.modelKey(leaves1.get(i)).equals(MaterialUtils.modelKey(leaves2.get(j)))
                        && sameLayoutEntry(layout1.get(i), layout2.get(j))) {
                    used[j] = true;
                    found = true;
                }
            }
            if (!found) {
                return false;
            }
        }
        return true;
    }

    private static List<double[]> getGroupLayout(HomeFurnitureGroup group) {
        List<double[]> layout = new ArrayList<>();
        double cos = Math.cos(-group.getAngle());
        double sin = Math.sin(-group.getAngle());
        for (HomePieceOfFurniture piece : MaterialUtils.leaves(group)) {
            double dx = piece.getX() - group.getX();
            double dy = piece.getY() - group.getY();
            layout.add(new double[]{
                dx * cos - dy * sin, dx * sin + dy * cos, piece.getElevation() - group.getElevation(),
                piece.getAngle() - group.getAngle(), widthInPlan(piece), depthInPlan(piece), heightInPlan(piece),
                piece.isModelMirrored() ? 1 : 0, piece.getPitch(), piece.getRoll()});
        }
        return layout;
    }

    private static boolean sameLayoutEntry(double[] a, double[] b) {
        final double lengthTolerance = 0.5;
        for (int k : new int[]{0, 1, 2, 4, 5, 6}) {
            if (Math.abs(a[k] - b[k]) > lengthTolerance) {
                return false;
            }
        }
        return sameAngle((float) a[3], (float) b[3])
                && sameAngle((float) a[8], (float) b[8])
                && sameAngle((float) a[9], (float) b[9])
                && a[7] == b[7];
    }

    boolean isMatching(HomePieceOfFurniture candidate, HomePieceOfFurniture source, List<String> properties) {
        for (String prop : properties) {
            boolean same;
            switch (prop) {
                case "model": same = isSameModel(candidate, source); break;
                case "objname":
                case "name":
                    same = Objects.equals(candidate.getName() != null ? candidate.getName() : "",
                            source.getName() != null ? source.getName() : "");
                    break;
                case "x": case "y": case "z": case "width": case "depth": case "height":
                    same = nearlyEqual((float) propertyValue(candidate, prop), (float) propertyValue(source, prop));
                    break;
                case "angle": same = sameAngle(candidate.getAngle(), source.getAngle()); break;
                case "pitch": same = sameAngle(candidate.getPitch(), source.getPitch()); break;
                case "roll": same = sameAngle(candidate.getRoll(), source.getRoll()); break;
                case "level":
                    same = candidate.getLevel() == source.getLevel()
                            || candidate.getLevel() != null && source.getLevel() != null
                            && Objects.equals(candidate.getLevel().getName(), source.getLevel().getName());
                    break;
                case "light_power":

                    HomeLight cl = firstLight(candidate), sl = firstLight(source);
                    same = cl != null && sl != null ? nearlyEqual(cl.getPower(), sl.getPower()) : (cl != null) == (sl != null);
                    break;
                case "color_texture": same = sameProperty(candidate, source, MaterialUtils.APPEARANCE); break;
                case "shininess": same = sameProperty(candidate, source, MaterialUtils.SHININESS); break;
                case "mirrored_property": same = candidate.isModelMirrored() == source.isModelMirrored(); break;
                case "movable_property": same = candidate.isMovable() == source.isMovable(); break;
                default: same = true; break;
            }
            if (!same) {
                return false;
            }
        }
        return true;
    }

    private static boolean nearlyEqual(float a, float b) {
        return Math.abs(a - b) <= EPSILON;
    }

    private static boolean sameAngle(float a, float b) {
        double twoPi = 2 * Math.PI;
        double diff = Math.abs((a - b) % twoPi);
        return diff <= 0.002 || Math.abs(diff - twoPi) <= 0.002;
    }

    private boolean sameValue(Object v1, Object v2, int property) {
        return MaterialUtils.sameValue(v1, v2, property, shininessTolerance, textureMatchByImage);
    }

    /** Compares appearance or shininess between two pieces. */
    private boolean sameProperty(HomePieceOfFurniture candidate, HomePieceOfFurniture source, int property) {
        if (!searchSettings[property].whole) {
            return sameMaterials(candidate, source, property);
        }
        Object v1 = valueOf(candidate.getColor(), candidate.getTexture(), candidate.getShininess(), property);
        Object v2 = valueOf(source.getColor(), source.getTexture(), source.getShininess(), property);
        if (v1 != null || v2 != null) {
            return sameValue(v1, v2, property);
        }
        if (isSameModel(candidate, source)
                && candidate.getModelMaterials() == null && source.getModelMaterials() == null) {
            return true;
        }
        Map<String, Object> m1 = MaterialUtils.effectiveMaterials(candidate, property);
        Map<String, Object> m2 = MaterialUtils.effectiveMaterials(source, property);
        if (m1.isEmpty() || m2.isEmpty() || m1.size() != m2.size()) {
            return false;
        }
        for (Map.Entry<String, Object> entry : m1.entrySet()) {
            if (!m2.containsKey(entry.getKey()) || !sameValue(entry.getValue(), m2.get(entry.getKey()), property)) {
                return false;
            }
        }
        return true;
    }

    private static Object valueOf(Integer color, HomeTexture texture, Float shininess, int property) {
        return property == MaterialUtils.APPEARANCE ? MaterialUtils.appearance(color, texture) : shininess;
    }

    private boolean sameMaterials(HomePieceOfFurniture candidate, HomePieceOfFurniture source, int property) {
        for (String material : searchSettings[property].materials) {
            Object v1 = MaterialUtils.valueOf(candidate, material, property);
            Object v2 = MaterialUtils.valueOf(source, material, property);
            if (!sameValue(v1, v2, property)) {
                return false;
            }
        }
        return true;
    }

    private void showSearchSettings(java.awt.Window parent, String key) {
        int property = "shininess".equals(key) ? MaterialUtils.SHININESS : MaterialUtils.APPEARANCE;
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JSpinner shininessSpinner = null;
        JRadioButton byImage = null;
        if (property == MaterialUtils.APPEARANCE) {
            JRadioButton byName = new JRadioButton(text("textureByName", "Same texture name"), !textureMatchByImage);
            byImage = new JRadioButton(text("textureByImage", "Same texture image"), textureMatchByImage);
            ButtonGroup group = new ButtonGroup();
            group.add(byName);
            group.add(byImage);
            panel.add(byName);
            panel.add(byImage);
        } else {
            shininessSpinner = new JSpinner(new SpinnerNumberModel(
                    (double) Math.round(shininessTolerance * 100), 0.0, 100.0, 1.0));
            panel.add(new JLabel(text("shininessTolerance", "Tolerance (0-100 %):")));
            panel.add(shininessSpinner);
        }
        ReplacerSettings.MaterialSearch result = ReplacerDialogs.showMaterialSearchDialog(parent, this::text, key,
                property, searchSettings[property], getOriginMaterials(property, false), panel);
        if (result != null) {
            searchSettings[property] = result;
            if (byImage != null) {
                textureMatchByImage = byImage.isSelected();
            } else {
                ReplacerDialogs.commit(shininessSpinner);
                shininessTolerance = ((Number) shininessSpinner.getValue()).floatValue() / 100f;
            }
            refreshPlanPreview();
        }
    }

    static int maxGroupDepth(HomeFurnitureGroup group) {
        int max = 1;
        for (HomePieceOfFurniture piece : group.getFurniture()) {
            if (piece instanceof HomeFurnitureGroup) {
                max = Math.max(max, 1 + maxGroupDepth((HomeFurnitureGroup) piece));
            }
        }
        return max;
    }

    private void updatePlanMatchPreview() {
        if (replaceAllMatching == null || selectMatchingInPlanCheck == null) {
            return;
        }
        Collection<HomePieceOfFurniture> items;
        if (replaceAllMatching.isSelected() && selectMatchingInPlanCheck.isSelected()) {
            items = getPreviewItemsToBeReplaced();
        } else if (!replacementPieces.isEmpty()) {
            items = replacementPieces;
        } else {
            items = initialSelectedFurniture != null
                    ? initialSelectedFurniture : Collections.<HomePieceOfFurniture>emptySet();
        }
        withoutSelectionEvents(() -> getHome().setSelectedItems(filterInHome(items)));
    }

    private List<HomePieceOfFurniture> getPreviewItemsToBeReplaced() {
        List<HomePieceOfFurniture> items = new ArrayList<>();
        if (initialSelectedFurniture != null && !initialSelectedFurniture.isEmpty()) {
            preview = items;
            try {
                replaceMatchingFurn(null, null);
            } finally {
                preview = null;
            }
        }
        return items;
    }
}
