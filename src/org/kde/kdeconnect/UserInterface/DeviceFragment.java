/*
 * Copyright 2014 Albert Vaca Cintora <albertvaka@gmail.com>
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License as
 * published by the Free Software Foundation; either version 2 of
 * the License or (at your option) version 3 or any later version
 * accepted by the membership of KDE e.V. (or its successor approved
 * by the membership of KDE e.V.), which shall act as a proxy
 * defined in Section 14 of version 3 of the license.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package org.kde.kdeconnect.UserInterface;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.support.v4.app.Fragment;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ListView;
import android.widget.TextView;

import org.kde.kdeconnect.BackgroundService;
import org.kde.kdeconnect.Device;
import org.kde.kdeconnect.Helpers.NetworkHelper;
import org.kde.kdeconnect.Helpers.SecurityHelpers.SslHelper;
import org.kde.kdeconnect.Plugins.Plugin;
import org.kde.kdeconnect.UserInterface.List.CustomItem;
import org.kde.kdeconnect.UserInterface.List.ListAdapter;
import org.kde.kdeconnect.UserInterface.List.PluginItem;
import org.kde.kdeconnect.UserInterface.List.SmallEntryItem;
import org.kde.kdeconnect_tp.R;

import java.util.ArrayList;
import java.util.Collection;
import java.util.ConcurrentModificationException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;


/**
 * Main view. Displays the current device and its plugins
 */
public class DeviceFragment extends Fragment {

    private static final String ARG_DEVICE_ID = "deviceId";
    private static final String ARG_FROM_DEVICE_LIST = "fromDeviceList";

    private View rootView;
    private static String mDeviceId; //Static because if we get here by using the back button in the action bar, the extra deviceId will not be set.
    private Device device;

    private MainActivity mActivity;

    private ArrayList<ListAdapter.Item> pluginListItems;

    public DeviceFragment() {
    }

    public DeviceFragment(String deviceId, boolean fromDeviceList) {
        Bundle args = new Bundle();
        args.putString(ARG_DEVICE_ID, deviceId);
        args.putBoolean(ARG_FROM_DEVICE_LIST, fromDeviceList);
        this.setArguments(args);
    }

    private DeviceFragment(String deviceId, MainActivity activity) {
        this.mActivity = activity;
        Bundle args = new Bundle();
        args.putString(ARG_DEVICE_ID, deviceId);
        this.setArguments(args);
    }

    @Override
    public void onAttach(Activity activity) {
        super.onAttach(activity);
        mActivity = ((MainActivity) getActivity());
    }

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
                             Bundle savedInstanceState) {

        rootView = inflater.inflate(R.layout.activity_device, container, false);

        final String deviceId = getArguments().getString(ARG_DEVICE_ID);
        if (deviceId != null) {
            mDeviceId = deviceId;
        }

        setHasOptionsMenu(true);

        //Log.e("DeviceFragment", "device: " + deviceId);

        BackgroundService.RunCommand(mActivity, service -> {
            device = service.getDevice(mDeviceId);
            if (device == null) {
                Log.e("DeviceFragment", "Trying to display a device fragment but the device is not present");
                mActivity.onDeviceSelected(null);
                return;
            }

            mActivity.getSupportActionBar().setTitle(device.getName());

            device.addPairingCallback(pairingCallback);
            device.addPluginsChangedListener(pluginsChangedListener);

            refreshUI();

        });

        final Button pairButton = rootView.findViewById(R.id.pair_button);
        pairButton.setOnClickListener(view -> {
            pairButton.setVisibility(View.GONE);
            ((TextView) rootView.findViewById(R.id.pair_message)).setText("");
            rootView.findViewById(R.id.pair_progress).setVisibility(View.VISIBLE);
            BackgroundService.RunCommand(mActivity, service -> {
                device = service.getDevice(deviceId);
                if (device == null) return;
                device.requestPairing();
            });
        });

        rootView.findViewById(R.id.accept_button).setOnClickListener(view -> BackgroundService.RunCommand(mActivity, service -> {
            if (device != null) {
                device.acceptPairing();
                rootView.findViewById(R.id.pairing_buttons).setVisibility(View.GONE);
            }
        }));

        rootView.findViewById(R.id.reject_button).setOnClickListener(view -> BackgroundService.RunCommand(mActivity, service -> {
            if (device != null) {
                //Remove listener so buttons don't show for a while before changing the view
                device.removePluginsChangedListener(pluginsChangedListener);
                device.removePairingCallback(pairingCallback);
                device.rejectPairing();
            }
            mActivity.onDeviceSelected(null);
        }));

        return rootView;
    }

    private final Device.PluginsChangedListener pluginsChangedListener = device -> refreshUI();

    @Override
    public void onDestroyView() {
        BackgroundService.RunCommand(mActivity, service -> {
            Device device = service.getDevice(mDeviceId);
            if (device == null) return;
            device.removePluginsChangedListener(pluginsChangedListener);
            device.removePairingCallback(pairingCallback);
        });
        super.onDestroyView();
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {

        //Log.e("DeviceFragment", "onPrepareOptionsMenu");

        super.onPrepareOptionsMenu(menu);
        menu.clear();

        if (device == null) {
            return;
        }


        //Plugins button list
        final Collection<Plugin> plugins = device.getLoadedPlugins().values();
        for (final Plugin p : plugins) {
            if (!p.displayInContextMenu()) {
                continue;
            }
            menu.add(p.getActionName()).setOnMenuItemClickListener(item -> {
                p.startMainActivity(mActivity);
                return true;
            });
        }

        menu.add(R.string.device_menu_plugins).setOnMenuItemClickListener(menuItem -> {
            Intent intent = new Intent(mActivity, DeviceSettingsActivity.class);
            intent.putExtra("deviceId", mDeviceId);
            startActivity(intent);
            return true;
        });

        if (device.isReachable()) {

            menu.add(R.string.encryption_info_title).setOnMenuItemClickListener(menuItem -> {
                Context context = mActivity;
                AlertDialog.Builder builder = new AlertDialog.Builder(context);
                builder.setTitle(context.getResources().getString(R.string.encryption_info_title));
                builder.setPositiveButton(context.getResources().getString(R.string.ok), (dialog, id) -> dialog.dismiss());

                if (device.certificate == null) {
                    builder.setMessage(R.string.encryption_info_msg_no_ssl);
                } else {
                    builder.setMessage(context.getResources().getString(R.string.my_device_fingerprint) + "\n" + SslHelper.getCertificateHash(SslHelper.certificate) + "\n\n"
                            + context.getResources().getString(R.string.remote_device_fingerprint) + "\n" + SslHelper.getCertificateHash(device.certificate));
                }
                builder.create().show();
                return true;
            });
        }

        if (device.isPaired()) {

            menu.add(R.string.device_menu_unpair).setOnMenuItemClickListener(menuItem -> {
                //Remove listener so buttons don't show for a while before changing the view
                device.removePluginsChangedListener(pluginsChangedListener);
                device.removePairingCallback(pairingCallback);
                device.unpair();
                mActivity.onDeviceSelected(null);
                return true;
            });
        }

    }

    @Override
    public void onResume() {
        super.onResume();

        getView().setFocusableInTouchMode(true);
        getView().requestFocus();
        getView().setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_UP && keyCode == KeyEvent.KEYCODE_BACK) {
                boolean fromDeviceList = getArguments().getBoolean(ARG_FROM_DEVICE_LIST, false);
                // Handle back button so we go to the list of devices in case we came from there
                if (fromDeviceList) {
                    mActivity.onDeviceSelected(null);
                    return true;
                }
            }
            return false;
        });
    }

    private void refreshUI() {
        //Log.e("DeviceFragment", "refreshUI");

        if (device == null || rootView == null) {
            return;
        }

        //Once in-app, there is no point in keep displaying the notification if any
        device.hidePairingNotification();

        mActivity.runOnUiThread(new Runnable() {
            @Override
            public void run() {

                if (device.isPairRequestedByPeer()) {
                    ((TextView) rootView.findViewById(R.id.pair_message)).setText(R.string.pair_requested);
                    rootView.findViewById(R.id.pairing_buttons).setVisibility(View.VISIBLE);
                    rootView.findViewById(R.id.pair_progress).setVisibility(View.GONE);
                    rootView.findViewById(R.id.pair_button).setVisibility(View.GONE);
                    rootView.findViewById(R.id.pair_request).setVisibility(View.VISIBLE);
                } else {

                    boolean paired = device.isPaired();
                    boolean reachable = device.isReachable();
                    boolean onData = NetworkHelper.isOnMobileNetwork(DeviceFragment.this.getContext());

                    rootView.findViewById(R.id.pairing_buttons).setVisibility(paired ? View.GONE : View.VISIBLE);
                    rootView.findViewById(R.id.error_message_container).setVisibility((paired && !reachable) ? View.VISIBLE : View.GONE);
                    rootView.findViewById(R.id.not_reachable_message).setVisibility((paired && !reachable && !onData) ? View.VISIBLE : View.GONE);
                    rootView.findViewById(R.id.on_data_message).setVisibility((paired && !reachable && onData) ? View.VISIBLE : View.GONE);

                    try {
                        pluginListItems = new ArrayList<>();

                        if (paired && reachable) {
                            //Plugins button list
                            final Collection<Plugin> plugins = device.getLoadedPlugins().values();
                            for (final Plugin p : plugins) {
                                if (!p.hasMainActivity()) continue;
                                if (p.displayInContextMenu()) continue;

                                pluginListItems.add(new PluginItem(p, v -> p.startMainActivity(mActivity)));
                            }

                            DeviceFragment.this.createPluginsList(device.getFailedPlugins(), R.string.plugins_failed_to_load, (plugin) -> plugin.getErrorDialog(mActivity).show());
                            DeviceFragment.this.createPluginsList(device.getPluginsWithoutPermissions(), R.string.plugins_need_permission, (plugin) -> plugin.getPermissionExplanationDialog(mActivity).show());
                            DeviceFragment.this.createPluginsList(device.getPluginsWithoutOptionalPermissions(), R.string.plugins_need_optional_permission, (plugin) -> plugin.getOptionalPermissionExplanationDialog(mActivity).show());
                        }

                        ListView buttonsList = rootView.findViewById(R.id.buttons_list);
                        ListAdapter adapter = new ListAdapter(mActivity, pluginListItems);
                        buttonsList.setAdapter(adapter);

                        mActivity.invalidateOptionsMenu();

                    } catch (IllegalStateException e) {
                        e.printStackTrace();
                        //Ignore: The activity was closed while we were trying to update it
                    } catch (ConcurrentModificationException e) {
                        Log.e("DeviceActivity", "ConcurrentModificationException");
                        this.run(); //Try again
                    }

                }
            }
        });

    }

    private final Device.PairingCallback pairingCallback = new Device.PairingCallback() {

        @Override
        public void incomingRequest() {
            refreshUI();
        }

        @Override
        public void pairingSuccessful() {
            refreshUI();
        }

        @Override
        public void pairingFailed(final String error) {
            mActivity.runOnUiThread(() -> {
                if (rootView == null) return;
                ((TextView) rootView.findViewById(R.id.pair_message)).setText(error);
                rootView.findViewById(R.id.pair_progress).setVisibility(View.GONE);
                rootView.findViewById(R.id.pair_button).setVisibility(View.VISIBLE);
                rootView.findViewById(R.id.pair_request).setVisibility(View.GONE);
                refreshUI();
            });
        }

        @Override
        public void unpaired() {
            mActivity.runOnUiThread(() -> {
                if (rootView == null) return;
                ((TextView) rootView.findViewById(R.id.pair_message)).setText(R.string.device_not_paired);
                rootView.findViewById(R.id.pair_progress).setVisibility(View.GONE);
                rootView.findViewById(R.id.pair_button).setVisibility(View.VISIBLE);
                rootView.findViewById(R.id.pair_request).setVisibility(View.GONE);
                refreshUI();
            });
        }

    };

    private void createPluginsList(ConcurrentHashMap<String, Plugin> plugins, int headerText, FailedPluginListItem.Action action) {
        if (!plugins.isEmpty()) {

            TextView header = new TextView(mActivity);
            header.setPadding(
                    0,
                    ((int) (28 * getResources().getDisplayMetrics().density)),
                    0,
                    ((int) (8 * getResources().getDisplayMetrics().density))
            );
            header.setOnClickListener(null);
            header.setOnLongClickListener(null);
            header.setText(headerText);

            pluginListItems.add(new CustomItem(header));
            for (Map.Entry<String, Plugin> entry : plugins.entrySet()) {
                String pluginKey = entry.getKey();
                final Plugin plugin = entry.getValue();
                if (device.isPluginEnabled(pluginKey)) {
                    if (plugin == null) {
                        pluginListItems.add(new SmallEntryItem(pluginKey));
                    } else {
                        pluginListItems.add(new FailedPluginListItem(plugin, action));
                    }
                }
            }
        }
    }
}
