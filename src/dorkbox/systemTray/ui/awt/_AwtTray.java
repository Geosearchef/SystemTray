/*
 * Copyright 2014 dorkbox, llc
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
package dorkbox.systemTray.ui.awt;

import java.awt.AWTException;
import java.awt.Image;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.io.File;

import javax.swing.ImageIcon;

import dorkbox.systemTray.MenuItem;
import dorkbox.systemTray.Tray;
import dorkbox.util.OS;
import dorkbox.util.SwingUtil;

/**
 * Class for handling all system tray interaction, via AWT. Pretty much EXCLUSIVELY for on MacOS, because that is the only time this
 * looks good and works correctly.
 *
 * It doesn't work well on linux. See bugs:
 * http://bugs.java.com/bugdatabase/view_bug.do?bug_id=6267936
 * http://bugs.java.com/bugdatabase/view_bug.do?bug_id=6453521
 * https://stackoverflow.com/questions/331407/java-trayicon-using-image-with-transparent-background/3882028#3882028
 *
 * Also, on linux, this WILL NOT CLOSE properly -- there is a frame handle that keeps the JVM open. MacOS does not have this problem.
 */
@SuppressWarnings({"SynchronizationOnLocalVariableOrMethodParameter", "WeakerAccess"})
public final
class _AwtTray extends Tray {
    private volatile SystemTray tray;
    private volatile TrayIcon trayIcon;

    // is the system tray visible or not.
    private volatile boolean visible = true;
    private volatile File imageFile;
    private volatile String tooltipText = "";

    private final Object keepAliveLock = new Object[0];
    private Thread keepAliveThread;

    // Called in the EDT
    public
    _AwtTray(final dorkbox.systemTray.SystemTray systemTray) {
        super();

        if (!SystemTray.isSupported()) {
            throw new RuntimeException("System Tray is not supported in this configuration! Please write an issue and include your OS " +
                                       "type and configuration");
        }

        // we override various methods, because each tray implementation is SLIGHTLY different. This allows us customization.
        final AwtMenu awtMenu = new AwtMenu(null) {
            @Override
            public
            void setEnabled(final MenuItem menuItem) {
                SwingUtil.invokeLater(new Runnable() {
                    @Override
                    public
                    void run() {
                        if (tray == null) {
                            tray = SystemTray.getSystemTray();
                        }

                        boolean enabled = menuItem.getEnabled();

                        if (OS.isMacOsX()) {
                            if (keepAliveThread != null) {
                                synchronized (keepAliveLock) {
                                    keepAliveLock.notifyAll();
                                }
                            }
                            keepAliveThread = null;

                            if (visible && !enabled) {
                                // THIS WILL NOT keep the app running, so we use a "keep-alive" thread so this behavior is THE SAME across
                                // all platforms. This was only noticed on MacOS (where the app would quit after calling setEnabled(false);
                                keepAliveThread = new Thread(new Runnable() {
                                    @Override
                                    public
                                    void run() {
                                        synchronized (keepAliveLock) {
                                            keepAliveLock.notifyAll();

                                            try {
                                                keepAliveLock.wait();
                                            } catch (InterruptedException ignored) {
                                            }
                                        }
                                    }
                                }, "TrayKeepAliveThread");
                                keepAliveThread.start();
                            }
                        }

                        if (visible && !enabled) {
                            tray.remove(trayIcon);
                            visible = false;
                        }
                        else if (!visible && enabled) {
                            try {
                                tray.add(trayIcon);
                                visible = true;

                                // don't want to matter which (setImage/setTooltip/setEnabled) is done first, and if the image/enabled is changed, we
                                // want to make sure keep the tooltip text the same as before.
                                trayIcon.setToolTip(tooltipText);
                            } catch (AWTException e) {
                                dorkbox.systemTray.SystemTray.logger.error("Error adding the icon back to the tray", e);
                            }
                        }
                    }
                });
            }

            @Override
            public
            void setImage(final MenuItem menuItem) {
                imageFile = menuItem.getImage();
                if (imageFile == null) {
                    return;
                }

                SwingUtil.invokeLater(new Runnable() {
                    @Override
                    public
                    void run() {
                        if (tray == null) {
                            tray = SystemTray.getSystemTray();
                        }

                        // stupid java won't scale it right away, so we have to do this twice to get the correct size
                        final Image trayImage = new ImageIcon(imageFile.getAbsolutePath()).getImage();
                        trayImage.flush();

                        if (trayIcon == null) {
                            // here we init. everything
                            trayIcon = new TrayIcon(trayImage);

                            trayIcon.setPopupMenu((PopupMenu) _native);

                            try {
                                tray.add(trayIcon);
                            } catch (AWTException e) {
                                dorkbox.systemTray.SystemTray.logger.error("TrayIcon could not be added.", e);
                            }
                        } else {
                            trayIcon.setImage(trayImage);
                        }

                        // don't want to matter which (setImage/setTooltip/setEnabled) is done first, and if the image/enabled is changed, we
                        // want to make sure keep the tooltip text the same as before.
                        trayIcon.setToolTip(tooltipText);
                    }
                });
            }

            @Override
            public
            void setText(final MenuItem menuItem) {
                // no op.
            }

            @Override
            public
            void setShortcut(final MenuItem menuItem) {
                // no op
            }

            @Override
            public
            void setTooltip(final MenuItem menuItem) {
                final String text = menuItem.getTooltip();

                if (tooltipText != null && tooltipText.equals(text) ||
                    tooltipText == null && text != null) {
                    return;
                }

                tooltipText = text;

                SwingUtil.invokeLater(new Runnable() {
                    @Override
                    public
                    void run() {
                        // don't want to matter which (setImage/setTooltip/setEnabled) is done first, and if the image/enabled is changed, we
                        // want to make sure keep the tooltip text the same as before.
                        if (trayIcon != null) {
                            trayIcon.setToolTip(text);
                        }
                    }
                });
            }

            @Override
            public
            void remove() {
                SwingUtil.invokeLater(new Runnable() {
                    @Override
                    public
                    void run() {
                        if (trayIcon != null) {
                            trayIcon.setPopupMenu(null);
                            if (tray != null) {
                                tray.remove(trayIcon);
                            }

                            trayIcon = null;
                        }

                        tray = null;
                    }
                });

                super.remove();
            }
        };

        bind(awtMenu, null, systemTray);
    }

    @Override
    public
    boolean hasImage() {
        return imageFile != null;
    }
}
