package ru.geekbrains.server;

import ru.geekbrains.connection.Message;
import ru.geekbrains.connection.MessageType;
import ru.geekbrains.connection.Network;
import ru.geekbrains.database.SQLService;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class ServerGuiController {
    private ServerGuiView gui;
    private ServerGuiModel model;
    private ServerSocket serverSocket;
    private volatile boolean isServerStart;

    public void run(ServerGuiController serverGuiController) {
        gui = new ServerGuiView(serverGuiController);
        model = new ServerGuiModel();
        gui.initComponents();
        while (true) {
            if (isServerStart) {
                serverGuiController.acceptServer();
                isServerStart = false;
            }
        }
    }

    protected void startServer(int port) {
        try {
            serverSocket = new ServerSocket(port);
            isServerStart = true;
            gui.refreshDialogWindowServer("Сервер запущен.\n");
        } catch (Exception e) {
            gui.refreshDialogWindowServer("Не удалось запустить сервер.\n");
        }
    }

    protected void stopServer() {
        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                for (Map.Entry<String, Network> user : model.getAllUsersChat().entrySet()) {
                    user.getValue().close();
                }
                serverSocket.close();
                model.getAllUsersChat().clear();
                isServerStart = false;
                gui.refreshDialogWindowServer("Сервер остановлен.\n");
            } else {
                gui.refreshDialogWindowServer("Сервер не запущен - нечего останавливать!\n");
            }
        } catch (Exception e) {
            gui.refreshDialogWindowServer("Сервер не может быть остановлен.\n");
        }
    }

    protected void acceptServer() {
        while (true) {
            try {
                new ServerThread(serverSocket.accept()).start();
            } catch (Exception e) {
                gui.refreshDialogWindowServer("Соединение с сервером потеряно.\n");
                break;
            }
        }
    }

    protected void sendMessageAllUsers(Message message) {
        for (Map.Entry<String, Network> user : model.getAllUsersChat().entrySet()) {
            try {
                user.getValue().send(message);
            } catch (Exception e) {
                gui.refreshDialogWindowServer("Ошибка отправки сообщения всем пользователям!\n");
            }
        }
    }

    protected void sendPrivateMessage(Message message) {
        for (Map.Entry<String, Network> user : model.getAllUsersChat().entrySet()) {
            try {
                String[] data = message.getTextMessage().split(" ");
                if (user.getKey().equals(data[0])) {
                    user.getValue().send(message);
                }
            } catch (Exception e) {
                gui.refreshDialogWindowServer("Ошибка отправки сообщения всем пользователям!\n");
            }
        }
    }

    public boolean isServerStart() {
        return isServerStart;
    }

    private class ServerThread extends Thread {

        private final Socket socket;

        public ServerThread(Socket socket) {
            this.socket = socket;
        }

        private String requestAndAddingUser(Network connection) {
            while (true) {
                try {
                    connection.send(new Message(MessageType.REQUEST_NICKNAME));
                    Message responseMessage = connection.receive();
                    String nickname = responseMessage.getTextMessage();
                    if (responseMessage.getTypeMessage() == MessageType.NICKNAME && nickname != null && !nickname.isEmpty() && !model.getAllUsersChat().containsKey(nickname)) {
                        model.addUser(nickname, connection);
                        Set<String> listUsers = new HashSet<>();
                        for (Map.Entry<String, Network> users : model.getAllUsersChat().entrySet()) {
                            listUsers.add(users.getKey());
                        }
                        connection.send(new Message(MessageType.NICKNAME_ACCEPTED, listUsers));
                        sendMessageAllUsers(new Message(MessageType.USER_ADDED, nickname));
                        return nickname;
                    } else {
                        connection.send(new Message(MessageType.NICKNAME_USED));
                    }
                } catch (Exception e) {
                    gui.refreshDialogWindowServer("При запросе и добавлении нового пользователя произошла ошибка.\n");
                    return null;
                }
            }
        }

        private void messagingBetweenUsers(Network network, String nickname) {
            while (true) {
                try {
                    Message message = network.receive();
                    if (message.getTypeMessage() == MessageType.TEXT_MESSAGE) {
                        sendMessage(nickname, message);
                        SQLService.savingUserMessages(String.format("%s: %s\n", nickname, message.getTextMessage()));
                    }
                    if (message.getTypeMessage() == MessageType.PRIVATE_TEXT_MESSAGE) {
                        sendPrivateMessage(new Message(MessageType.PRIVATE_TEXT_MESSAGE, message.getTextMessage() + " " + nickname));
                        SQLService.savingUserMessages("*" + message.getTextMessage() + " - (" + nickname + ")");
                    }
                    if (message.getTypeMessage() == MessageType.NICKNAME_CHANGED) {
                        String textMessage = String.format("%s сменил ник на %s", nickname, message.getTextMessage());
                        nicknameChanged(nickname, message);
                        SQLService.savingUserMessages(textMessage);
                    }
                    if (message.getTypeMessage() == MessageType.DISABLE_USER) {
                        disableUser(nickname, network);
                        SQLService.savingUserMessages((nickname + ": отключился"));
                        break;
                    }
                } catch (Exception e) {
                    gui.refreshDialogWindowServer(String.format("Произошла ошибка при отправке сообщения от пользователя %s, либо отключен!\n", nickname));
                    model.removeUser(nickname);
                    break;
                }
            }
        }

        private void sendMessage(String nickname, Message message) {
            String textMessage = String.format("%s: %s\n", nickname, message.getTextMessage());
            sendMessageAllUsers(new Message(MessageType.TEXT_MESSAGE, textMessage));
        }

        private void nicknameChanged(String nickname, Message message) {
            sendMessageAllUsers(new Message(MessageType.NICKNAME_CHANGED, String.format("%s сменил ник на %s", nickname, message.getTextMessage())));
            String tempName = nickname;
            Network tempConnection = model.getConnection(nickname);
            model.removeUser(tempName);
            nickname = message.getTextMessage();
            model.addUser(nickname, tempConnection);
        }

        private void disableUser(String nickname, Network network) throws IOException {
            sendMessageAllUsers(new Message(MessageType.REMOVED_USER, nickname));
            model.removeUser(nickname);
            network.close();
            gui.refreshDialogWindowServer(String.format("Пользователь удаленного доступа %s отключен.\n", socket.getRemoteSocketAddress()));
        }

        @Override
        public void run() {
            gui.refreshDialogWindowServer(String.format("Новый пользователь, подключенный к удаленному сокету - %s.\n", socket.getRemoteSocketAddress()));
            try {
                Network connection = new Network(socket);
                messagingBetweenUsers(connection, requestAndAddingUser(connection));
            } catch (Exception e) {
                gui.refreshDialogWindowServer("Произошла ошибка при отправке сообщения от пользователя!\n");
            }
        }

    }
}