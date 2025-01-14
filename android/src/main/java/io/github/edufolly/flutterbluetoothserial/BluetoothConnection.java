package io.github.edufolly.flutterbluetoothserial;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;
import java.util.Arrays;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothServerSocket;
import android.bluetooth.BluetoothSocket;

/// Universal Bluetooth serial connection class (for Java)
public abstract class BluetoothConnection {
    protected static final UUID DEFAULT_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB");

    protected BluetoothAdapter bluetoothAdapter;

    protected ConnectionThread connectionThread = null;

    public boolean isConnected() {
        return connectionThread != null && connectionThread.requestedClosing != true;
    }


    public BluetoothConnection(BluetoothAdapter bluetoothAdapter) {
        this.bluetoothAdapter = bluetoothAdapter;
    }


    // @TODO . `connect` could be done perfored on the other thread
    // @TODO . `connect` parameter: timeout
    // @TODO . `connect` other methods than `createRfcommSocketToServiceRecord`, including hidden one raw `createRfcommSocket` (on channel).
    // @TODO ? how about turning it into factoried?
    /// Connects to given device by hardware address
    public void connect(String address, UUID uuid) throws IOException {
        if (isConnected()) {
            throw new IOException("already connected");
        }

        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
        if (device == null) {
            throw new IOException("device not found");
        }

        BluetoothSocket socket = device.createRfcommSocketToServiceRecord(uuid); // @TODO . introduce ConnectionMethod
        if (socket == null) {
            throw new IOException("socket connection not established");
        }

        // Cancel discovery, even though we didn't start it
        bluetoothAdapter.cancelDiscovery();

        socket.connect();

        connectionThread = new ConnectionThread(socket, false, null, address);
        connectionThread.start();
    }

    /// Accepts an incoming connection
    public void listenForConnections(String sdpName, int timeout) throws IOException {
        if (isConnected()) {
            throw new IOException("already connected");
        }

        BluetoothServerSocket serverSocket = bluetoothAdapter
                .listenUsingRfcommWithServiceRecord(sdpName, DEFAULT_UUID);
        if (serverSocket == null) {
            throw new IOException("socket server could not established");
        }

        BluetoothSocket socket = null;

        try {
            socket = serverSocket.accept(timeout);
        } catch (IOException ex) {
            serverSocket.close();
            throw ex;
        }

        serverSocket.close();

        if (socket == null) {
            throw new IOException("socket connection not established");
        }

        connectionThread = new ConnectionThread(socket, true, sdpName, null);
        connectionThread.start();
    }

    /// Connects to given device by hardware address (default UUID used)
    public void connect(String address) throws IOException {
        connect(address, DEFAULT_UUID);
    }

    /// Disconnects current session (ignore if not connected)
    public void disconnect() {
        if (isConnected()) {
            connectionThread.cancel();
            connectionThread = null;
        }
    }

    /// Writes to connected remote device 
    public void write(byte[] data) throws IOException {
        if (!isConnected()) {
            throw new IOException("not connected");
        }

        connectionThread.write(data);
    }

    /// Callback for reading data.
    protected abstract void onRead(byte[] data);

    /// Callback for disconnection.
    protected abstract void onDisconnected(boolean byRemote);

    /// Thread to handle connection I/O
    private class ConnectionThread extends Thread {
        private static final int RECONNECT_SERVER_TIMEOUT = 20000;
        private static final int RECONNECT_SERVER_NUM_TRIES = 3;
        private static final int RECONNECT_CLIENT_NUM_TRIES = 10;
        private static final int RECONNECT_ATTEMPT_DELAY = 5000;

        private BluetoothSocket socket;
        // Need this to determine reconnect strategy if dc event occurs.
        private final boolean isListeningConnection;
        private final InputStream input;
        private final OutputStream output;
        private boolean requestedClosing = false;
        // sdpName if this was a listening connection
        private final String sdpName;
        // address if this was a client connection
        private final String address;

        ConnectionThread(BluetoothSocket socket, boolean isListeningConnection, String sdpName,
                         String address) {
            this.socket = socket;
            this.isListeningConnection = isListeningConnection;
            this.sdpName = sdpName;
            this.address = address;
            InputStream tmpIn = null;
            OutputStream tmpOut = null;

            try {
                tmpIn = socket.getInputStream();
                tmpOut = socket.getOutputStream();
            } catch (IOException e) {
                e.printStackTrace();
            }

            this.input = tmpIn;
            this.output = tmpOut;
        }

        /// Thread main code
        public void run() {
            byte[] buffer = new byte[1024];
            int bytes;

            while (!requestedClosing) {
                try {
                    bytes = input.read(buffer);

                    onRead(Arrays.copyOf(buffer, bytes));
                } catch (IOException e) {
                    if (isListeningConnection) {
                        BluetoothSocket newSocket = null;
                        BluetoothServerSocket serverSocket = null;
                        int retryCount = RECONNECT_SERVER_NUM_TRIES;
                        while (newSocket == null && retryCount > 0) {
                            retryCount--;
                            try {
                                serverSocket = bluetoothAdapter
                                        .listenUsingRfcommWithServiceRecord(sdpName, DEFAULT_UUID);
                                newSocket = serverSocket.accept(RECONNECT_SERVER_TIMEOUT);
                            } catch (IOException exception) {
                                if(serverSocket != null) {
                                    try {
                                        serverSocket.close();
                                    } catch (IOException ex) {
                                        ex.printStackTrace();
                                    }
                                }
                            }
                        }
                        if(newSocket != null) {
                            try {
                                this.socket.close();
                            } catch (IOException ex) {
                                ex.printStackTrace();
                            }
                            this.socket = newSocket;
                            if(serverSocket != null) {
                                try {
                                    serverSocket.close();
                                } catch (IOException ex) {
                                    ex.printStackTrace();
                                }
                            }
                            // If connection couldn't be reestablished end the connection
                        } else {
                            break;
                        }
                        // if client connection
                    } else {
                        BluetoothSocket newSocket = null;
                        int retryCount = RECONNECT_CLIENT_NUM_TRIES;
                        BluetoothDevice device = bluetoothAdapter.getRemoteDevice(address);
                        while (newSocket == null && retryCount > 0) {
                            retryCount--;
                            try {
                                newSocket = device.createRfcommSocketToServiceRecord(DEFAULT_UUID);
                                if(newSocket == null) {
                                    continue;
                                }
                                newSocket.connect();
                            } catch (IOException ex) {
                                if(newSocket != null) {
                                    try {
                                        newSocket.close();
                                    } catch (IOException exc) {
                                        newSocket = null;
                                    }
                                }
                            }
                            try {
                                Thread.sleep(RECONNECT_ATTEMPT_DELAY);
                            } catch (InterruptedException ex) {
                                ex.printStackTrace();
                            }
                        }
                        if(newSocket != null) {
                            try {
                                this.socket.close();
                            } catch (IOException ex) {
                                ex.printStackTrace();
                            }
                            this.socket = newSocket;
                        } else {
                            break;
                        }
                    }
                }
            }

            // Make sure output stream is closed
            if (output != null) {
                try {
                    output.close();
                } catch (Exception e) {
                }
            }

            // Make sure input stream is closed
            if (input != null) {
                try {
                    input.close();
                } catch (Exception e) {
                }
            }

            // Callback on disconnected, with information which side is closing
            onDisconnected(!requestedClosing);

            // Just prevent unnecessary `cancel`ing
            requestedClosing = true;
        }

        /// Writes to output stream
        public void write(byte[] bytes) {
            try {
                output.write(bytes);
            } catch (IOException e) {
                e.printStackTrace();
            }
        }

        /// Stops the thread, disconnects
        public void cancel() {
            if (requestedClosing) {
                return;
            }
            requestedClosing = true;

            // Flush output buffers befoce closing
            try {
                output.flush();
            } catch (Exception e) {
            }

            // Close the connection socket
            if (socket != null) {
                try {
                    // Might be useful (see https://stackoverflow.com/a/22769260/4880243)
                    Thread.sleep(111);

                    socket.close();
                } catch (Exception e) {
                }
            }
        }
    }
}
