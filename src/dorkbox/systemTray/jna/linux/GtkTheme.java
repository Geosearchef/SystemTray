/*
 * Copyright 2017 dorkbox, llc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package dorkbox.systemTray.jna.linux;

import static dorkbox.systemTray.util.CssParser.injectAdditionalCss;
import static dorkbox.systemTray.util.CssParser.removeComments;

import java.awt.Color;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.PrintStream;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.jna.Pointer;
import com.sun.jna.ptr.PointerByReference;

import dorkbox.systemTray.SystemTray;
import dorkbox.systemTray.Tray;
import dorkbox.systemTray.jna.linux.structs.GdkColor;
import dorkbox.systemTray.jna.linux.structs.GdkRGBAColor;
import dorkbox.systemTray.jna.linux.structs.GtkRequisition;
import dorkbox.systemTray.jna.linux.structs.GtkStyle;
import dorkbox.systemTray.jna.linux.structs.PangoRectangle;
import dorkbox.systemTray.util.CssParser;
import dorkbox.systemTray.util.CssParser.Css;
import dorkbox.systemTray.util.CssParser.CssNode;
import dorkbox.systemTray.util.CssParser.Entry;
import dorkbox.util.FileUtil;
import dorkbox.util.MathUtil;
import dorkbox.util.OS;
import dorkbox.util.OSUtil;
import dorkbox.util.process.ShellProcessBuilder;

/**
 * Class to contain all of the methods needed to get the text color from the AppIndicator/GtkStatusIcon menu entry. This is primarily
 * used to get the color needed for the checkmark icon. In GTK, the checkmark icon can be defined to be it's OWN color and
 * shape, however getting/parsing that would be even significantly more difficult -- so we decided to make the icon the same color
 * as the text.
 * <p>
 * Additionally, CUSTOM, user theme modifications in ~/.gtkrc-2.0 (for example), will be ignored.
 *
 * Also note: not all themes have CSS or Theme files!!!
 */
@SuppressWarnings({"deprecation", "WeakerAccess"})
public
class GtkTheme {
    private static final boolean DEBUG = false;
    private static final boolean DEBUG_SHOW_CSS = false;
    private static final boolean DEBUG_VERBOSE = false;

    // CSS nodes that we care about, in oder of preference from left to right.
    private static final
    String[] cssNodes = new String[] {".menuitem", ".entry", "*"};

    public static
    Rectangle getPixelTextHeight(String text) {
        // have to use pango to get the size of text (for the checkmark size)
        Pointer offscreen = Gtk.gtk_offscreen_window_new();

        // we use the size of "X" as the checkmark
        Pointer item = Gtk.gtk_image_menu_item_new_with_mnemonic(text);

        Gtk.gtk_container_add(offscreen, item);

        // get the text widget (GtkAccelLabel) from inside the GtkMenuItem
        Pointer textLabel = Gtk.gtk_bin_get_child(item);
        Pointer pangoLayout = Gtk.gtk_label_get_layout(textLabel);

        // ink pixel size is how much exact space it takes on the screen
        PangoRectangle ink = new PangoRectangle();

        Gtk.pango_layout_get_pixel_extents(pangoLayout, ink.getPointer(), null);
        ink.read();

        Rectangle size = new Rectangle(ink.width, ink.height);

        Gtk.gtk_widget_destroy(item);
        Gtk.gtk_widget_destroy(offscreen);

        return size;
    }

    public static
    Rectangle getLogicalTextHeight(String text) {
        // have to use pango to get the size of text (for the checkmark size)
        Pointer offscreen = Gtk.gtk_offscreen_window_new();

        // we use the size of "X" as the checkmark
        Pointer item = Gtk.gtk_image_menu_item_new_with_mnemonic(text);

        Gtk.gtk_container_add(offscreen, item);

        // get the text widget (GtkAccelLabel) from inside the GtkMenuItem
        Pointer textLabel = Gtk.gtk_bin_get_child(item);
        Pointer pangoLayout = Gtk.gtk_label_get_layout(textLabel);

        // logical pixel size (ascent + descent)
        PangoRectangle logical = new PangoRectangle();

        Gtk.pango_layout_get_pixel_extents(pangoLayout, null, logical.getPointer());
        logical.read();

        Rectangle size = new Rectangle(logical.width, logical.height);

        Gtk.gtk_widget_destroy(item);
        Gtk.gtk_widget_destroy(offscreen);

        return size;
    }

    /**
     * @return the size of the GTK menu entry's IMAGE, as best as we can tell, for determining how large of icons to use for the menu entry
     */
    public static
    int getMenuEntryImageSize() {
        final AtomicReference<Integer> imageHeight = new AtomicReference<Integer>();

        GtkEventDispatch.dispatchAndWait(new Runnable() {
            @Override
            public
            void run() {
                Pointer offscreen = Gtk.gtk_offscreen_window_new();

                // get the default icon size for the "paste" icon.
                Pointer item = Gtk.gtk_image_menu_item_new_from_stock("gtk-paste", null);

                Gtk.gtk_container_add(offscreen, item);

                PointerByReference r = new PointerByReference();
                Gobject.g_object_get(item, "image", r.getPointer(), null);

                Pointer imageWidget = r.getValue();
                GtkRequisition gtkRequisition = new GtkRequisition();
                Gtk.gtk_widget_size_request(imageWidget, gtkRequisition.getPointer());
                gtkRequisition.read();

                imageHeight.set(gtkRequisition.height);
            }
        });

        int height = imageHeight.get();
        if (height > 0) {
            return height;
        }
        else {
            return 16; // who knows?
        }
    }

    /**
     * Gets the system tray indicator size.
     *  - AppIndicator:  will properly scale the image if it's not the correct size
     *  - GtkStatusIndicator:  ??
     */
    public static
    int getIndicatorSize(final Class<? extends Tray> trayType) {
        // Linux is similar enough, that it just uses this method
        // https://wiki.archlinux.org/index.php/HiDPI

        // 96 DPI is the default
        final double defaultDPI = 96.0;

        final AtomicReference<Double> screenScale = new AtomicReference<Double>();
        final AtomicInteger screenDPI = new AtomicInteger();

        GtkEventDispatch.dispatchAndWait(new Runnable() {
            @Override
            public
            void run() {
                // screen DPI
                Pointer screen = Gtk.gdk_screen_get_default();
                if (screen != null) {
                    // this call makes NO SENSE, but reading the documentation shows it is the CORRECT call.
                    screenDPI.set((int) Gtk.gdk_screen_get_resolution(screen));
                }

                if (Gtk.isGtk3) {
                    Pointer window = Gtk.gdk_get_default_root_window();
                    if (window != null) {
                        screenScale.set((double) Gtk3.gdk_window_get_scale_factor(window));
                    }
                }
            }
        });

        // fallback
        if (screenDPI.get() == 0) {
            // GET THE DPI IN LINUX
            // https://wiki.archlinux.org/index.php/Talk:GNOME
            Object detectedValue = Toolkit.getDefaultToolkit().getDesktopProperty("gnome.Xft/DPI");
            if (detectedValue instanceof Integer) {
                int dpi = ((Integer) detectedValue) / 1024;
                if (dpi == -1) {
                    screenDPI.set((int) defaultDPI);
                }
                if (dpi < 50) {
                    // 50 dpi is the minimum value gnome allows
                    screenDPI.set(50);
                }
            }
        }


        // check system ENV variables.
        if (screenScale.get() == 0) {
            String envVar = System.getenv("QT_AUTO_SCREEN_SCALE_FACTOR");
            if (envVar != null) {
                try {
                    screenScale.set(Double.parseDouble(envVar));
                } catch (Exception ignored) {
                }
            }
        }

        // check system ENV variables.
        if (screenScale.get() == 0) {
            String envVar = System.getenv("QT_SCALE_FACTOR");
            if (envVar != null) {
                try {
                    screenScale.set(Double.parseDouble(envVar));
                } catch (Exception ignored) {
                }
            }
        }

        // check system ENV variables.
        if (screenScale.get() == 0) {
            String envVar = System.getenv("GDK_SCALE");
            if (envVar != null) {
                try {
                    screenScale.set(Double.parseDouble(envVar));
                } catch (Exception ignored) {
                }
            }
        }

        // check system ENV variables.
        if (screenScale.get() == 0) {
            String envVar = System.getenv("ELM_SCALE");
            if (envVar != null) {
                try {
                    screenScale.set(Double.parseDouble(envVar));
                } catch (Exception ignored) {
                }
            }
        }



        OSUtil.DesktopEnv.Env env = OSUtil.DesktopEnv.get();
        // sometimes the scaling-factor is set
        if (env == OSUtil.DesktopEnv.Env.Gnome) {
            try {
                ByteArrayOutputStream byteArrayOutputStream = new ByteArrayOutputStream(8196);
                PrintStream outputStream = new PrintStream(byteArrayOutputStream);

                // gsettings get org.gnome.desktop.interface scaling-factor
                final ShellProcessBuilder shellVersion = new ShellProcessBuilder(outputStream);
                shellVersion.setExecutable("gsettings");
                shellVersion.addArgument("get");
                shellVersion.addArgument("org.gnome.desktop.interface");
                shellVersion.addArgument("scaling-factor");
                shellVersion.start();

                String output = ShellProcessBuilder.getOutput(byteArrayOutputStream);

                if (!output.isEmpty()) {
                    // DEFAULT icon size is 16. HiDpi changes this scale, so we should use it as well.
                    // should be: uint32 0  or something
                    if (output.contains("uint32")) {
                        String value = output.substring(output.indexOf("uint") + 7, output.length());

                        // 0 is disabled (no scaling)
                        // 1 is enabled (default scale)
                        // 2 is 2x scale
                        // 3 is 3x scale
                        // etc

                        double scalingFactor = Double.parseDouble(value);
                        if (scalingFactor >= 1) {
                            screenScale.set(scalingFactor);
                        }

                        // A setting of 2, 3, etc, which is all you can do with scaling-factor
                        // To enable HiDPI, use gsettings:
                        // gsettings set org.gnome.desktop.interface scaling-factor 2
                    }
                }
            } catch (Throwable ignore) {
            }
        }
        else if (OSUtil.DesktopEnv.isKDE()) {
            // check the custom KDE override file
            try {
                File customSettings = new File("/usr/bin/startkde-custom");
                if (customSettings.canRead()) {
                    List<String> lines = FileUtil.readLines(customSettings);
                    for (String line : lines) {
                        String str = "export GDK_SCALE=";
                        int i = line.indexOf(str);
                        if (i > -1) {
                            String scale = line.substring(i + str.length());
                            double scalingFactor = Double.parseDouble(scale);
                            if (scalingFactor >= 1) {
                                screenScale.set(scalingFactor);
                                break;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {
            }
        }

//        System.err.println("screen scale: " + screenScale.get());
//        System.err.println("screen DPI: " + screenDPI.get());

        if (OSUtil.DesktopEnv.isKDE()) {
            /*
             *
             * Looking in  plasma-framework/src/declarativeimports/core/units.cpp:
    // Scale the icon sizes up using the devicePixelRatio
    // This function returns the next stepping icon size
    // and multiplies the global settings with the dpi ratio.
    const qreal ratio = devicePixelRatio();

    if (ratio < 1.5) {
        return size;
    } else if (ratio < 2.0) {
        return size * 1.5;
    } else if (ratio < 2.5) {
        return size * 2.0;
    } else if (ratio < 3.0) {
        return size * 2.5;
    } else if (ratio < 3.5) {
        return size * 3.0;
    } else {
        return size * ratio;
    }
My ratio is 1.47674, that means I have no scaling at all when there is a 1.5 factor existing. Is it reasonable? Wouldn't it make more sense to use the factor the closest to the ratio rather than  what is done here?

             */

            File mainFile = new File("/usr/share/plasma/plasmoids/org.kde.plasma.private.systemtray/contents/config/main.xml");
            if (mainFile.canRead()) {
                List<String> lines = FileUtil.readLines(mainFile);
                boolean found = false;
                int index = 0;
                for (final String line : lines) {
                    if (line.contains("<entry name=\"iconSize\" type=\"Int\">")) {
                        found = true;
                        // have to get the "default" line value
                    }

                    String str = "<default>";
                    if (found && (index = line.indexOf(str)) > -1) {
                        // this is our line. now get the value.
                        String substring = line.substring(index + str.length(), line.indexOf("</default>", index));

                        if (MathUtil.isInteger(substring)) {
                            // Default icon size for the systray icons, it's an enum which values mean,
                            // Small, SmallMedium, Medium, Large, Huge, Enormous respectively.
                            // On low DPI systems they correspond to :
                            //    16, 22, 32, 48, 64, 128 pixels.
                            // On high DPI systems those values would be scaled up, depending on the DPI.
                            int imageSize = 0;
                            int imageSizeEnum = Integer.parseInt(substring);
                            switch (imageSizeEnum) {
                                case 0:
                                    imageSize = 16;
                                    break;
                                case 1:
                                    imageSize = 22;
                                    break;
                                case 2:
                                    imageSize = 32;
                                    break;
                                case 3:
                                    imageSize = 48;
                                    break;
                                case 4:
                                    imageSize = 64;
                                    break;
                                case 5:
                                    imageSize = 128;
                                    break;
                            }

                            if (imageSize > 0) {
                                double scaleRatio = screenDPI.get() / defaultDPI;

                                return (int) (scaleRatio * imageSize);
                            }
                        }
                    }
                }
            }
        }
        else {
            if (OSUtil.Linux.isUbuntu() && env == OSUtil.DesktopEnv.Env.Unity) {
                // if we measure on ubuntu unity using a screen shot (using swing, so....) , the max size was 24, HOWEVER this goes from
                // the top->bottom of the indicator bar -- and since it was swing, it uses a different rendering method and it (honestly)
                // looks weird, because there is no padding for the icon. The official AppIndicator size is hardcoded...
                // http://bazaar.launchpad.net/~indicator-applet-developers/libindicator/trunk.16.10/view/head:/libindicator/indicator-image-helper.c

                return 22;
            }
            else {
                // xfce is easy, because it's not a GTK setting for the size  (xfce notification area maximum icon size)
                if (env == OSUtil.DesktopEnv.Env.XFCE) {
                    String properties = OSUtil.DesktopEnv.queryXfce("xfce4-panel", null);
                    List<String> propertiesAsList = Arrays.asList(properties.split(OS.LINE_SEPARATOR));
                    for (String prop : propertiesAsList) {
                        if (prop.startsWith("/plugins/") && prop.endsWith("/size-max")) {
                            // this is the property we are looking for (we just don't know which panel it's on)

                            String size = OSUtil.DesktopEnv.queryXfce("xfce4-panel", prop);
                            try {
                                return Integer.parseInt(size);
                            } catch (Exception e) {
                                SystemTray.logger.error("Unable to get XFCE notification panel size for channel '{}', property '{}'",
                                                        "xfce4-panel", prop, e);
                            }
                        }
                    }

                    // default...
                    return 22;
                }


                // try to use GTK to get the tray icon size
                final AtomicInteger traySize = new AtomicInteger();

                GtkEventDispatch.dispatchAndWait(new Runnable() {
                    @Override
                    public
                    void run() {
                        Pointer screen = Gtk.gdk_screen_get_default();
                        Pointer settings = null;

                        if (screen != null) {
                            settings = Gtk.gtk_settings_get_for_screen(screen);
                        }

                        if (settings != null) {
                            PointerByReference pointer = new PointerByReference();

                            // https://wiki.archlinux.org/index.php/GTK%2B
                            // To use smaller icons, use a line like this:
                            //    gtk-icon-sizes = "panel-menu=16,16:panel=16,16:gtk-menu=16,16:gtk-large-toolbar=16,16:gtk-small-toolbar=16,16:gtk-button=16,16"

                            // this gets icon sizes. On XFCE, ubuntu, it returns "panel-menu-bar=24,24"
                            // NOTE: gtk-icon-sizes is deprecated and ignored since GTK+ 3.10.

                            // A list of icon sizes. The list is separated by colons, and item has the form: size-name = width , height
                            Gobject.g_object_get(settings, "gtk-icon-sizes", pointer.getPointer(), null);

                            Pointer value = pointer.getValue();
                            if (value != null) {
                                String iconSizes = value.getString(0);
                                String[] strings = new String[] {"panel-menu-bar=", "panel=", "gtk-large-toolbar=", "gtk-small-toolbar="};
                                for (String var : strings) {
                                    int i = iconSizes.indexOf(var);
                                    if (i >= 0) {
                                        String size = iconSizes.substring(i + var.length(), iconSizes.indexOf(",", i));

                                        if (MathUtil.isInteger(size)) {
                                            traySize.set(Integer.parseInt(size));
                                            return;
                                        }
                                    }
                                }
                            }
                        }
                    }
                });

                int i = traySize.get();
                if (i != 0) {
                    return i;
                }
            }
        }

        // sane default
        return 22;
    }

    /**
     * @return the widget color of text for the current theme, or black. It is important that this is called AFTER GTK has been initialized.
     */
    public static
    Color getTextColor() {
        // NOTE: when getting CSS, we redirect STDERR to null (via GTK), so that we won't spam the console if there are parse errors.
        //   this is a horrid hack, but the only way to work around the errors we have no control over. The parse errors, if bad enough
        //   just mean that we are unable to get the CSS as we want.

        // these methods are from most accurate (but limited in support) to compatible across Linux OSes.. Strangely enough, GTK makes
        // this information insanely difficult to get.
        final AtomicReference<Color> color = new AtomicReference<Color>(null);
        GtkEventDispatch.dispatchAndWait(new Runnable() {
            @SuppressWarnings("UnusedAssignment")
            @Override
            public
            void run() {
                Color c;

                // see if we can get the info via CSS properties (> GTK+ 3.2 uses an API, GTK2 gets it from disk).
                // This is often the BEST way to get information, since GTK **DOES NOT** make it easy to get widget information BEFORE
                // the widget is realized -- which in our case, we must do.
                c = getColorFromCss();
                if (c != null) {
                    if (DEBUG) {
                        System.err.println("Got from CSS");
                    }
                    color.set(c);
                    return;
                }


                // try to get via the color scheme.
                c = getFromColorScheme();
                if (c != null) {
                    if (DEBUG) {
                        System.err.println("Got from color scheme");
                    }
                    color.set(c);
                    return;
                }


                // if we get here, this means that there was NO "gtk-color-scheme" value in the theme file.
                // This usually happens when the theme does not have @fg_color (for example), but instead has each color explicitly
                // defined for every widget instance in the theme file. Old/bizzare themes tend to do it this way...
                if (Gtk.isGtk2) {
                    c = getFromGtk2ThemeText();
                    if (c != null) {
                        if (DEBUG) {
                            System.err.println("Got from gtk2 color theme file");
                        }
                        color.set(c);
                        return;
                    }
                }


                // the following methods all require an offscreen widget to get the style information from.

                // create an off-screen widget (don't forget to destroy everything!)
                Pointer offscreen = Gtk.gtk_offscreen_window_new();
                Pointer item = Gtk.gtk_image_menu_item_new_with_mnemonic("a");

                Gtk.gtk_container_add(offscreen, item);
                Gtk.gtk_widget_show_all(item);

                // Try to get via RC style... Sometimes this works (sometimes it does not...)
                {
                    Pointer style = Gtk.gtk_rc_get_style(item);

                    GdkColor gdkColor = new GdkColor();
                    boolean success = false;

                    success = Gtk.gtk_style_lookup_color(style, "menu_fg_color", gdkColor.getPointer());
                    if (!success) {
                        success = Gtk.gtk_style_lookup_color(style, "text_color", gdkColor.getPointer());
                        if (success) {
                            System.err.println("a");
                        }
                    }
                    if (!success) {
                        success = Gtk.gtk_style_lookup_color(style, "theme_text_color", gdkColor.getPointer());
                        if (success) {
                            System.err.println("a");
                        }
                    }
                    if (success) {
                        c = gdkColor.getColor();
                    }
                }

                if (c != null) {
                    if (DEBUG) {
                        System.err.println("Got from gtk offscreen gtk_style_lookup_color");
                    }
                    color.set(c);
                    Gtk.gtk_widget_destroy(item);
                    return;
                }



                if (Gtk.isGtk3) {
                    Pointer context = Gtk3.gtk_widget_get_style_context(item);

                    GdkRGBAColor gdkColor = new GdkRGBAColor();
                    boolean success = false;

                    success = Gtk3.gtk_style_context_lookup_color(context, "fg_color", gdkColor.getPointer());
                    if (!success) {
                        success = Gtk3.gtk_style_context_lookup_color(context, "text_color", gdkColor.getPointer());
                    }
                    if (!success) {
                        success = Gtk3.gtk_style_context_lookup_color(context, "menu_fg_color", gdkColor.getPointer());
                    }

                    if (!success) {
                        success = Gtk3.gtk_style_context_lookup_color(context, "color", gdkColor.getPointer());
                    }

                    if (success) {
                        c = gdkColor.getColor();
                    }
                }

                if (c != null) {
                    color.set(c);
                    if (DEBUG) {
                        System.err.println("Got from gtk3 offscreen gtk_widget_get_style_context");
                    }
                    Gtk.gtk_widget_destroy(item);
                    return;
                }

                // this doesn't always work...
                GtkStyle.ByReference style = Gtk.gtk_widget_get_style(item);
                color.set(style.text[Gtk.State.NORMAL].getColor());

                if (DEBUG) {
                    System.err.println("Got from gtk gtk_widget_get_style");
                }

                Gtk.gtk_widget_destroy(item);
            }
        });


        Color c = color.get();
        if (c != null) {
            if (DEBUG) {
                System.err.println("COLOR FOUND: " + c);
            }
            return c;
        }

        SystemTray.logger.error("Unable to determine the text color in use by your system. Please create an issue and include your " +
                                "full OS configuration and desktop environment, including theme details, such as the theme name, color " +
                                "variant, and custom theme options (if any).");

        // who knows WHAT the color is supposed to be. This is just a "best guess" default value.
        return Color.BLACK;
    }

    /**
     * get the color we are interested in via raw CSS parsing. This is specifically to get the color of the text of the
     * appindicator/gtk-status-icon menu.
     * <p>
     * > GTK+ 3.2 uses an API, GTK2 gets it from disk
     *
     * @return the color string, parsed from CSS,
     */
    private static
    Color getColorFromCss() {
        Css css = getCss();
        if (css != null) {
            if (DEBUG_SHOW_CSS) {
                System.err.println(css);
            }

            try {
                // collect a list of all of the sections that have what we are interested in.
                List<CssNode> sections = CssParser.getSections(css, cssNodes, null);
                List<Entry> colorStrings = CssParser.getAttributeFromSections(sections, "color", true);

                String colorString = CssParser.selectMostRelevantAttribute(cssNodes, colorStrings);

                if (colorString != null) {
                    if (colorString.startsWith("@")) {
                        // it's a color definition
                        String colorSubString = css.getColorDefinition(colorString.substring(1));
                        return parseColor(colorSubString);
                    }
                    else {
                        return parseColor(colorString);
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return null;
    }

    /**
     * @return the CSS for the current theme or null. It is important that this is called AFTER GTK has been initialized.
     */
    public static
    Css getCss() {
        String css;
        if (Gtk.isLoaded && Gtk.isGtk3) {
            final AtomicReference<String> css_ = new AtomicReference<String>(null);

            GtkEventDispatch.dispatchAndWait(new Runnable() {
                @Override
                public
                void run() {
                    String themeName = getThemeName();

                    if (themeName != null) {
                        Pointer value = Gtk3.gtk_css_provider_get_named(themeName, null);
                        if (value != null) {
                            // we have the css provider!

                            // NOTE: This can output warnings if the theme doesn't parse correctly by GTK, so we suppress them
                            Glib.GLogFunc orig = Glib.g_log_set_default_handler(Glib.nullLogFunc, null);

                            css_.set(Gtk3.gtk_css_provider_to_string(value));

                            Glib.g_log_set_default_handler(orig, null);
                        }
                    }
                    else {
                        Pointer value = Gtk3.gtk_css_provider_get_default();
                        if (value != null) {
                            // we have the css provider!

                            // NOTE: This can output warnings if the theme doesn't parse correctly by GTK, so we suppress them
                            Glib.GLogFunc orig = Glib.g_log_set_default_handler(Glib.nullLogFunc, null);

                            css_.set(Gtk3.gtk_css_provider_to_string(value));

                            Glib.g_log_set_default_handler(orig, null);
                        }
                    }
                }
            });

            // will be either the string, or null.
            css = css_.get();
        }
        else {
            // GTK2 has to get the GTK3 theme text a different way (parsing it from disk). SOMETIMES, the app must be GTK2, even though
            // the system is GTK3. This works around the API restriction if we are an APP in GTK2 mode. This is not done ALL the time,
            // because this is not as accurate as using the GTK3 API.
            // This can also be a requirement if GTK is not loaded
            css = getGtk3ThemeCssViaFile();
        }

        return CssParser.parse(css);
    }

    /**
     * this works for GtkStatusIcon menus.
     *
     * @return the menu_fg/fg/text color from gtk-color-scheme or null
     */
    public static
    Color getFromColorScheme() {
        Pointer screen = Gtk.gdk_screen_get_default();
        Pointer settings = null;

        if (screen != null) {
            settings = Gtk.gtk_settings_get_for_screen(screen);
        }

        if (settings != null) {
            // see if we can get the info we want the EASY way (likely only when GTK+ 2 is used, but can be < GTK+ 3.2)...

            //  been deprecated since version 3.8
            PointerByReference pointer = new PointerByReference();
            Gobject.g_object_get(settings, "gtk-color-scheme", pointer.getPointer(), null);


            // A palette of named colors for use in themes. The format of the string is
            //      name1: color1
            //      name2: color2
            //
            //  Color names must be acceptable as identifiers in the gtkrc syntax, and color specifications must be in the format
            //      accepted by gdk_color_parse().
            //
            // Note that due to the way the color tables from different sources are merged, color specifications will be converted
            // to hexadecimal form when getting this property.
            //
            //  Starting with GTK+ 2.12, the entries can alternatively be separated by ';' instead of newlines:
            //      name1: color1; name2: color2; ...
            //
            // GtkSettings:gtk-color-scheme has been deprecated since version 3.8 and should not be used in newly-written code.
            //  Color scheme support was dropped and is no longer supported. You can still set this property, but it will be ignored.


            Pointer value = pointer.getValue();
            if (value != null) {
                String s = value.getString(0);
                if (!s.isEmpty()) {
                    if (DEBUG) {
                        System.out.println("\t string: " + s);
                    }

                    // Note: these are the values on my system when forcing GTK+ 2 (XUbuntu 16.04) with GtkStatusIcon and Aidwata theme
                    // bg_color_dark: #686868686868
                    // fg_color: #3c3c3c3c3c3c
                    // fm_color: #f7f7f7f7f7f7
                    // selected_fg_color: #ffffffffffff
                    // panel_bg: #686868686868
                    // text_color: #212121212121
                    // text_color_dark: #ffffffffffff
                    // tooltip_bg_color: #000000000000
                    // link_color: #2d2d7171b8b8
                    // tooltip_fg_color: #e1e1e1e1e1e1
                    // base_color: #fcfcfcfcfcfc
                    // bg_color: #cececececece
                    // selected_bg_color: #39398e8ee7e7

                    // list of colors, in order of importance, that we want to parse.
                    String colors[] = new String[] {"menu_fg_color", "fg_color", "text_color"};

                    for (String colorName : colors) {
                        int i = 0;
                        while (i != -1) {
                            i = s.indexOf(colorName, i);
                            if (i >= 0) {
                                try {
                                    // the color will ALWAYS be in hex notation

                                    // it is also possible to be separated by ; instead of newline
                                    int endIndex = s.indexOf(';', i);
                                    if (endIndex == -1) {
                                        endIndex = s.indexOf('\n', i);
                                    }

                                    if (s.charAt(i - 1) == '_') {
                                        i = endIndex;
                                        continue;
                                    }

                                    int startIndex = s.indexOf('#', i);
                                    String colorString = s.substring(startIndex, endIndex)
                                                          .trim();

                                    if (DEBUG_VERBOSE) {
                                        System.out.println("Color string: " + colorString);
                                    }
                                    return parseColor(colorString);
                                } catch (Exception ignored) {
                                }
                            }
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Checks in the following locations for the current GTK3 theme.
     * <p>
     * /usr/share/themes
     * /opt/gnome/share/themes
     */
    private static
    String getGtk3ThemeCssViaFile() {
        File themeDirectory = getThemeDirectory(true);

        if (themeDirectory == null) {
            return null;
        }

        File gtkFile = new File(themeDirectory, "gtk.css");
        try {
            StringBuilder stringBuilder = new StringBuilder((int) (gtkFile.length()));
            FileUtil.read(gtkFile, stringBuilder);

            removeComments(stringBuilder);

            // only comments in the file
            if (stringBuilder.length() < 2) {
                return null;
            }

            injectAdditionalCss(themeDirectory, stringBuilder);

            return stringBuilder.toString();
        } catch (IOException e) {
            // cant read the file or something else.
            if (SystemTray.DEBUG) {
                SystemTray.logger.error("Error getting RAW GTK3 theme file.", e);
            }
        }

        return null;
    }

    /**
     * @return the discovered fg[NORMAL] or text[NORMAL] color for this theme or null
     */
    public static
    Color getFromGtk2ThemeText() {
        String gtk2ThemeText = getGtk2ThemeText();

        if (gtk2ThemeText != null) {
            String[] colorText = new String[] {"fg[NORMAL]", "text[NORMAL]"};
            for (String text : colorText) {
                int i = 0;
                while (i != -1) {
                    i = gtk2ThemeText.indexOf(text, i);
                    if (i != -1) {
                        if (i > 0 && gtk2ThemeText.charAt(i - 1) != '_') {
                            i += text.length();
                            continue;
                        }


                        int j = gtk2ThemeText.indexOf("=", i);
                        if (j != -1) {
                            int lineEnd = gtk2ThemeText.indexOf('\n', j);

                            if (lineEnd != -1) {
                                String colorName = gtk2ThemeText.substring(j + 1, lineEnd)
                                                                .trim();

                                colorName = colorName.replaceAll("\"", "");
                                return parseColor(colorName);
                            }
                        }
                    }
                }
            }
        }

        return null;
    }


    /**
     * Checks in the following locations for the current GTK2 theme.
     * <p>
     * /usr/share/themes
     * /opt/gnome/share/themes
     */
    private static
    String getGtk2ThemeText() {
        File themeDirectory = getThemeDirectory(false);

        if (themeDirectory == null) {
            return null;
        }


        // ie: /usr/share/themes/Numix/gtk-2.0/gtkrc
        File gtkFile = new File(themeDirectory, "gtkrc");

        try {
            StringBuilder stringBuilder = new StringBuilder((int) (gtkFile.length()));
            FileUtil.read(gtkFile, stringBuilder);

            removeComments(stringBuilder);

            // only comments in the file
            if (stringBuilder.length() < 2) {
                return null;
            }

            return stringBuilder.toString();
        } catch (IOException ignored) {
            // cant read the file or something else.
        }

        return null;
    }


    /**
     * Figures out what the directory is for the specified type of GTK theme files (css/gtkrc/etc)
     *
     * @param gtk3 true if you want to look for the GTK3 theme dir, false if you want the GTK2 theme dir
     *
     * @return the directory or null if it cannot be found
     */
    public static
    File getThemeDirectory(boolean gtk3) {
        String themeName = getThemeName();

        if (themeName == null) {
            return null;
        }

        String gtkType;
        if (gtk3) {
            gtkType = "gtk-3.0";
        }
        else {
            gtkType = "gtk-2.0";
        }


        String[] dirs = new String[] {"/usr/share/themes", "/opt/gnome/share/themes"};

        // ie: /usr/share/themes
        for (String dirName : dirs) {
            File themesDir = new File(dirName);

            File[] themeDirs = themesDir.listFiles();
            if (themeDirs != null) {
                // ie: /usr/share/themes/Numix
                for (File themeDir : themeDirs) {
                    File[] files1 = themeDir.listFiles();
                    if (files1 != null) {
                        boolean isCorrectTheme;

                        File themeIndexFile = new File(themeDir, "index.theme");
                        try {
                            List<String> read = FileUtil.read(themeIndexFile, false);
                            for (String s : read) {
                                if (s.startsWith("GtkTheme=")) {
                                    String calculatedThemeName = s.substring("GtkTheme=".length());

                                    isCorrectTheme = calculatedThemeName.equals(themeName);

                                    if (isCorrectTheme) {
                                        // ie: /usr/share/themes/Numix/gtk-3.0/gtk.css
                                        // the DARK variant is only used by some apps. The dark variant is NOT SYSTEM-WIDE!
                                        return new File(themeDir, gtkType);
                                    }

                                    break;
                                }
                            }
                        } catch (IOException ignored) {
                        }
                    }
                }
            }
        }

        return null;
    }

    /**
     * Parses out the color from a color:
     * <p>
     * - the word "transparent"
     * - hex 12 digit  #ffffaaaaffff
     * - hex 6 digit   #ffaaff
     * - hex 3 digit   #faf
     * - rgb(r, g, b)  rgb(33, 33, 33)
     * - rgb(r, g, b)  rgb(.6, .3, .3)
     * - rgb(r%, g%, b%)  rgb(10%, 20%, 30%)
     * - rgba(r, g, b, a)  rgb(33, 33, 33, 0.53)
     * - rgba(r, g, b, a)  rgb(.33, .33, .33, 0.53)
     * - rgba(r, g, b, a)  rgb(10%, 20%, 30%, 0.53)
     * <p>
     * Notes:
     * - rgb(), when an int, is between 0-255
     * - rgb(), when a float, is between 0.0-1.0
     * - rgb(), when a percent, is between 0-100
     * - alpha is always a float
     *
     * @return the parsed color
     */
    @SuppressWarnings("Duplicates")
    private static
    Color parseColor(String colorString) {
        if (colorString == null) {
            return null;
        }

        int red = 0;
        int green = 0;
        int blue = 0;
        int alpha = 255;

        if (colorString.startsWith("#")) {
            colorString = colorString.substring(1);

            if (colorString.length() > 11) {
                red = Integer.parseInt(colorString.substring(0, 4), 16);
                green = Integer.parseInt(colorString.substring(4, 8), 16);
                blue = Integer.parseInt(colorString.substring(8), 16);

                // Have to convert to positive int (value between 0 and 65535, these are 16 bits per pixel) that is from 0-255
                red = red & 0x0000FFFF;
                green = green & 0x0000FFFF;
                blue = blue & 0x0000FFFF;

                red = (red >> 8) & 0xFF;
                green = (green >> 8) & 0xFF;
                blue = (blue >> 8) & 0xFF;
            }
            else if (colorString.length() > 5) {
                red = Integer.parseInt(colorString.substring(0, 2), 16);
                green = Integer.parseInt(colorString.substring(2, 4), 16);
                blue = Integer.parseInt(colorString.substring(4), 16);
            }
            else {
                red = Integer.parseInt(colorString.substring(0, 1), 16);
                green = Integer.parseInt(colorString.substring(1, 2), 16);
                blue = Integer.parseInt(colorString.substring(2), 16);
            }
        }
        else if (colorString.startsWith("rgba")) {
            colorString = colorString.substring(colorString.indexOf('(') + 1, colorString.indexOf(')'));
            String[] split = colorString.split(",");

            String trim1 = split[0].trim();
            String trim2 = split[1].trim();
            String trim3 = split[2].trim();
            String trim4 = split[3].trim();

            if (colorString.contains("%")) {
                trim1 = trim1.replace("%", "");
                trim2 = trim2.replace("%", "");
                trim3 = trim3.replace("%", "");

                red = Integer.parseInt(trim1) * 255;
                green = Integer.parseInt(trim2) * 255;
                blue = Integer.parseInt(trim3) * 255;
            }
            else if (colorString.contains(".")) {
                red = (int) (Float.parseFloat(trim1) * 255);
                green = (int) (Float.parseFloat(trim2) * 255);
                blue = (int) (Float.parseFloat(trim3) * 255);
            }
            else {
                red = Integer.parseInt(trim1);
                green = Integer.parseInt(trim2);
                blue = Integer.parseInt(trim3);
            }

            float alphaF = Float.parseFloat(trim4);
            alpha = (int) (alphaF * 255);
        }
        else if (colorString.startsWith("rgb")) {
            colorString = colorString.substring(colorString.indexOf('(') + 1, colorString.indexOf(')'));
            String[] split = colorString.split(",");

            String trim1 = split[0].trim();
            String trim2 = split[1].trim();
            String trim3 = split[2].trim();

            if (colorString.contains("%")) {
                trim1 = trim1.replace("%", "");
                trim2 = trim2.replace("%", "");
                trim3 = trim3.replace("%", "");

                red = Integer.parseInt(trim1) * 255;
                green = Integer.parseInt(trim2) * 255;
                blue = Integer.parseInt(trim3) * 255;
            }
            else if (colorString.contains(".")) {
                red = (int) (Float.parseFloat(trim1) * 255);
                green = (int) (Float.parseFloat(trim2) * 255);
                blue = (int) (Float.parseFloat(trim3) * 255);
            }
            else {
                red = Integer.parseInt(trim1);
                green = Integer.parseInt(trim2);
                blue = Integer.parseInt(trim3);
            }
        }
        else if (colorString.contains("transparent")) {
            alpha = 0;
        }
        else {
            int index = colorString.indexOf(";");
            if (index > 0) {
                colorString = colorString.substring(0, index);
            }
            colorString = colorString.replaceAll("\"", "");
            colorString = colorString.replaceAll("'", "");

            // maybe it's just a "color" description, such as "red"?
            try {
                return Color.decode(colorString);
            } catch (Exception e) {
                return null;
            }
        }

        return new Color(red, green, blue, alpha);
    }

    /**
     * https://wiki.archlinux.org/index.php/GTK%2B
     * <p>
     * gets the name of the currently loaded theme
     * GTK+ 2:
     * ~/.gtkrc-2.0
     * gtk-icon-theme-name = "Adwaita"
     * gtk-theme-name = "Adwaita"
     * gtk-font-name = "DejaVu Sans 11"
     * <p>
     * <p>
     * GTK+ 3:
     * $XDG_CONFIG_HOME/gtk-3.0/settings.ini
     * [Settings]
     * gtk-icon-theme-name = Adwaita
     * gtk-theme-name = Adwaita
     * gtk-font-name = DejaVu Sans 11
     * <p>
     * <p>
     * Note: The icon theme name is the name defined in the theme's index file, not the name of its directory.
     * <p>
     * directories:
     * /usr/share/themes
     * /opt/gnome/share/themes
     * <p>
     * GTK+ 2 user specific: ~/.gtkrc-2.0
     * GTK+ 2 system wide: /etc/gtk-2.0/gtkrc
     * <p>
     * GTK+ 3 user specific: $XDG_CONFIG_HOME/gtk-3.0/settings.ini, or $HOME/.config/gtk-3.0/settings.ini if $XDG_CONFIG_HOME is not set
     * GTK+ 3 system wide: /etc/gtk-3.0/settings.ini
     *
     * @return the theme name, or null if it cannot find it.
     */
    public static
    String getThemeName() {
        final AtomicReference<String> themeName = new AtomicReference<String>(null);

        GtkEventDispatch.dispatchAndWait(new Runnable() {
            @Override
            public
            void run() {
                Pointer screen = Gtk.gdk_screen_get_default();
                Pointer settings = null;

                if (screen != null) {
                    settings = Gtk.gtk_settings_get_for_screen(screen);
                }

                if (settings != null) {
                    PointerByReference pointer = new PointerByReference();
                    Gobject.g_object_get(settings, "gtk-theme-name", pointer.getPointer(), null);

                    Pointer value = pointer.getValue();
                    if (value != null) {
                        themeName.set(value.getString(0));
                    }
                }

                if (DEBUG) {
                    System.err.println("Theme name: " + themeName);
                }
            }
        });

        // will be either the string, or null.
        return themeName.get();

    }
}
