package ru.geekbrains.client;

import ru.geekbrains.connection.Message;
import ru.geekbrains.connection.MessageType;
import ru.geekbrains.connection.Network;
import ru.geekbrains.database.SQLService;
import ru.geekbrains.settings.Settings;
import ru.geekbrains.sound.MakeSound;
import ru.geekbrains.validator.Validator;

import java.io.IOException;
import java.net.Socket;
import java.sql.SQLException;

public class ClientGuiController {
    private Network connection;
    private ClientGuiModel model;
    private ClientGuiView view;

    private volatile boolean clientConnected;
    private String nickname;
    private boolean isDatabaseConnected;

    public void run(ClientGuiController clientGuiController) {
        model = new ClientGuiModel();
        view = new ClientGuiView(clientGuiController);
        view.initComponents();
        while (true) {
            if (clientGuiController.isClientConnected()) {
                clientGuiController.userNameRegistration();
                clientGuiController.receiveMessageFromServer();
                clientGuiController.setClientConnected(false);
            }
        }
    }

    protected void userNameRegistration() {
        while (true) {
            try {
                Message message = connection.receive();
                if (message.getTypeMessage() == MessageType.REQUEST_NICKNAME) {
                    nickname = SQLService.getNickname(nickname);
                    connection.send(new Message(MessageType.NICKNAME, nickname));
                }
                if (message.getTypeMessage() == MessageType.NICKNAME_USED) {
                    view.errorDialogWindow("Пользователь с таким именем уже есть в чате.");
                    disableClient();
                    break;
                }
                if (message.getTypeMessage() == MessageType.NICKNAME_ACCEPTED) {
                    view.addMessage(String.format("Ваше имя принято (%s)\n", nickname));
                    model.setUsers(message.getListUsers());
                    break;
                }
            } catch (Exception e) {
                e.printStackTrace();
                view.errorDialogWindow("Произошла ошибка при регистрации имени. Попробуйте переподключиться.");
                try {
                    connection.close();
                    clientConnected = false;
                    break;
                } catch (IOException ex) {
                    view.errorDialogWindow("Ошибка закрытия соединения.");
                }
            }
        }
    }

    protected void receiveMessageFromServer() {
        while (clientConnected) {
            try {
                Message message = connection.receive();
                if (message.getTypeMessage() == MessageType.TEXT_MESSAGE) {
                    processIncomingMessage(message);
                }
                if (message.getTypeMessage() == MessageType.NICKNAME_CHANGED) {
                    notifyNicknameChanged(message);
                }
                if (message.getTypeMessage() == MessageType.PRIVATE_TEXT_MESSAGE) {
                    processingOfPrivateMessagesForSending(message);
                }
                if (message.getTypeMessage() == MessageType.USER_ADDED) {
                    informAboutAddingNewUser(message);
                }
                if (message.getTypeMessage() == MessageType.REMOVED_USER) {
                    informAboutDeletingNewUser(message);
                }
            } catch (Exception e) {
                view.errorDialogWindow("Произошла ошибка при получении сообщения от сервера.");
                setClientConnected(false);
                view.refreshListUsers(model.getAllNickname());
                break;
            }
        }
    }

    public boolean isClientConnected() {
        return clientConnected;
    }

    public void setClientConnected(boolean clientConnected) {
        this.clientConnected = clientConnected;
    }

    protected void informAboutAddingNewUser(Message message) {
        model.addUser(message.getTextMessage());
        MakeSound.playSound(Settings.SOUND_URL_CONNECTED);
        view.refreshListUsers(model.getAllNickname());
        view.addMessage(String.format("(%s) присоединился к чату.\n", message.getTextMessage()));
    }

    protected void informAboutDeletingNewUser(Message message) {
        model.deleteUser(message.getTextMessage());
        MakeSound.playSound(Settings.SOUND_URL_DISCONNECT);
        view.refreshListUsers(model.getAllNickname());
        view.addMessage(String.format("(%s) покинул чат.\n", message.getTextMessage()));
    }

    protected void processingOfPrivateMessagesForSending(Message message) {
        String[] data = message.getTextMessage().split(" ");
        StringBuilder formattingForSendingPrivateMessage = new StringBuilder();
        for (int i = 1; i < data.length - 1; i++) {
            formattingForSendingPrivateMessage.append(data[i]).append(" ");
        }
        view.addMessage(String.format("Личное сообщение от (%s): %s\n", data[data.length - 1], formattingForSendingPrivateMessage.toString()));
    }

    protected void notifyNicknameChanged(Message message) {
        String[] data = message.getTextMessage().split(" ");
        view.addMessage(message.getTextMessage() + "\n");
        model.deleteUser(data[0]);
        model.addUser(data[data.length - 1]);
        view.refreshListUsers(model.getAllNickname());
    }

    protected void processIncomingMessage(Message message) {
        view.addMessage(message.getTextMessage());
    }

    protected void disableClient() {
        try {
            if (clientConnected) {
                connection.send(new Message(MessageType.DISABLE_USER));
                model.getAllNickname().clear();
                clientConnected = false;
                view.refreshListUsers(model.getAllNickname());
                view.addMessage("Вы отключились от сервера.\n");
            } else {
                view.errorDialogWindow("Вы уже отключены.");
            }
        } catch (Exception e) {
            view.errorDialogWindow("Произошла ошибка при отключении.");
        }
    }

    protected void connectToServer() {
        if (!clientConnected) {
            while (true) {
                try {
                    connection = new Network(new Socket(view.getServerAddress(), view.getPort()));
                    clientConnected = true;
                    view.addMessage("Вы подключились к серверу.\n");
                    break;
                } catch (Exception e) {
                    view.errorDialogWindow("Произошла ошибка! Возможно, вы ввели неправильный адрес сервера или порт. Попробуйте еще раз.");
                    break;
                }
            }
        } else {
            view.errorDialogWindow("Вы уже подключены!");
        }
    }

    public void setNickname(String nickname) {
        this.nickname = nickname;
    }

    protected void sendMessageOnServer(String text) {
        try {
            connection.send(new Message(MessageType.TEXT_MESSAGE, text));
        } catch (Exception e) {
            view.errorDialogWindow("Ошибка отправки сообщения.");
        }
    }

    protected void sendPrivateMessageOnServer(String userSelected, String text) {
        try {
            if (!nickname.equals(userSelected)) {
                view.addMessage(String.format("Личное сообщение отправлено пользователю (%s)\n", userSelected));
                connection.send(new Message(MessageType.PRIVATE_TEXT_MESSAGE, text));
            } else {
                view.errorDialogWindow("Вы не можете отправить личное сообщение самому себе.");
            }
        } catch (Exception e) {
            view.errorDialogWindow("Ошибка отправки сообщения.");
        }
    }

    public void changeNickname() {
        String newNickname = view.getNickname();
        if (newNickname != null) {
            try {
                if (Validator.isValidNickname(newNickname) && SQLService.changeNick(nickname, newNickname)) {
                    model.deleteUser(nickname);
                    nickname = newNickname;
                    model.addUser(newNickname);
                    view.refreshListUsers(model.getAllNickname());
                    try {
                        connection.send(new Message(MessageType.NICKNAME_CHANGED, newNickname));
                    } catch (IOException e) {
                        view.errorDialogWindow(e.getMessage());
                    }
                } else {
                    view.errorDialogWindow("Пожалуйста, введите правильные данные.");
                }
            } catch (SQLException e) {
                view.errorDialogWindow("Не удалось изменить имя, похоже, что пользователь с таким именем уже вошел в систему.");
            }
        }
    }

    public boolean isDatabaseConnected() {
        return isDatabaseConnected;
    }

    public void setDatabaseConnected(boolean databaseConnected) {
        isDatabaseConnected = databaseConnected;
    }
}
