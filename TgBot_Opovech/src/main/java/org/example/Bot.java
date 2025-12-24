package org.example;

import org.telegram.telegrambots.bots.TelegramLongPollingBot;
import org.telegram.telegrambots.meta.api.methods.send.SendMessage;
import org.telegram.telegrambots.meta.api.objects.Update;
import org.telegram.telegrambots.meta.api.objects.Message;
import org.telegram.telegrambots.meta.api.objects.CallbackQuery;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.InlineKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.ReplyKeyboardMarkup;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.InlineKeyboardButton;
import org.telegram.telegrambots.meta.api.objects.replykeyboard.buttons.KeyboardRow;

import java.util.*;
import java.util.concurrent.*;

/**
 * CRM‑бот для работы с заявками клиентов по недвижимости.
 * Хранение пока в памяти (List), для продакшена лучше вынести в БД.
 */
public class Bot extends TelegramLongPollingBot {

    /* ===================== МОДЕЛЬ КЛИЕНТА ===================== */

    private static class Client {
        String id = UUID.randomUUID().toString().substring(0, 6);
        String name;
        String phone;
        String city;
        Status status = Status.NEW;

        enum Status {
            NEW,        // Предстоящие задачи
            WAITING,    // Связались, ждём ответа
            COMPLETED,  // Выполнено
            DELETED     // Удалено (можно восстановить)
        }
    }

    /* ===================== ПОЛЯ СОСТОЯНИЯ БОТА ===================== */

    // "БД" в памяти
    private final List<Client> clients = new ArrayList<>();

    // В каком шаге ввода сейчас находится пользователь (NAME / PHONE / CITY)
    private final Map<Long, String> inputStates = new HashMap<>();

    // Черновик заявки, который пользователь заполняет по шагам
    private final Map<Long, Client> draftClients = new HashMap<>();

    // Планировщик напоминаний
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2);

    /* ===================== НАСТРОЙКИ БОТА ===================== */

    @Override
    public String getBotUsername() {
        // Имя бота, которое видно в Telegram (из BotFather)
        return "8503308692:AAHI_7_1tobJuMx29jVt1bCspju7zCstOJ8";
    }

    @Override
    public String getBotToken() {
        // ЗДЕСЬ должен быть токен, но лучше брать из переменных окружения
        // return System.getenv("TELEGRAM_BOT_TOKEN");
        return "8503308692:AAHI_7_1tobJuMx29jVt1bCspju7zCstOJ8";
    }

    /* ===================== ТОЧКА ВХОДА ДЛЯ ОБНОВЛЕНИЙ ===================== */

    @Override
    public void onUpdateReceived(Update update) {
        if (update.hasMessage() && update.getMessage().hasText()) {
            Message msg = update.getMessage();
            handleMessage(msg.getChatId(), msg.getText());
        } else if (update.hasCallbackQuery()) {
            CallbackQuery cb = update.getCallbackQuery();
            handleCallback(cb.getMessage().getChatId(), cb.getData());
        }
    }

    /* ===================== ОБРАБОТКА ТЕКСТОВЫХ СООБЩЕНИЙ ===================== */

    private void handleMessage(long chatId, String text) {

        // 1. Если пользователь находится в процессе заполнения заявки — продолжаем диалог
        if (inputStates.containsKey(chatId)) {
            processClientInput(chatId, text);
            return;
        }

        // 2. Главное меню
        switch (text) {
            case "/start" -> sendMainMenu(
                    chatId,
                    "Добро пожаловать в CRM по работе с клиентами по недвижимости.\n" +
                            "Выберите действие из меню ниже:"
            );
            case "📝 Создать заявку" -> {
                inputStates.put(chatId, "NAME");
                draftClients.put(chatId, new Client());
                sendMessage(chatId, "Введите ФИО клиента:");
            }
            case "📂 Предстоящие" -> showClientList(chatId, Client.Status.NEW, "Предстоящие задачи:");
            case "⏳ В ожидании" -> showClientList(chatId, Client.Status.WAITING, "Клиенты, от которых ждём ответ:");
            case "✅ Выполненные" -> showClientList(chatId, Client.Status.COMPLETED, "Выполненные задачи:");
            case "🗑 Удаленные" -> showClientList(chatId, Client.Status.DELETED, "Удалённые задачи (можно восстановить):");
            default -> sendMessage(chatId, "Не понял команду.\nПожалуйста, используйте кнопки меню внизу экрана.");
        }
    }

    /* ===================== ПОШАГОВЫЙ ВВОД ЗАЯВКИ ===================== */

    private void processClientInput(long chatId, String text) {
        Client client = draftClients.get(chatId);
        String state = inputStates.get(chatId);

        switch (state) {
            case "NAME" -> {
                client.name = text;
                inputStates.put(chatId, "PHONE");
                sendMessage(chatId, "Введите номер телефона клиента:");
            }
            case "PHONE" -> {
                client.phone = text;
                inputStates.put(chatId, "CITY");
                sendMessage(chatId, "Введите город, где клиент хочет купить недвижимость:");
            }
            case "CITY" -> {
                client.city = text;
                clients.add(client);

                inputStates.remove(chatId);
                draftClients.remove(chatId);

                sendMainMenu(chatId,
                        "✅ Заявка сохранена и добавлена в раздел «Предстоящие».\n" +
                                "Выберите следующее действие:");
            }
            default -> {
                // На всякий случай сбрасываем состояние, если что-то пошло не так
                inputStates.remove(chatId);
                draftClients.remove(chatId);
                sendMainMenu(chatId, "Произошла ошибка состояния. Попробуйте создать заявку заново.");
            }
        }
    }

    /* ===================== ОТОБРАЖЕНИЕ СПИСКОВ КЛИЕНТОВ ===================== */

    private void showClientList(long chatId, Client.Status status, String header) {
        List<Client> filtered = clients.stream()
                .filter(c -> c.status == status)
                .toList();

        if (filtered.isEmpty()) {
            sendMessage(chatId, header + "\n\nСписок пуст.");
            return;
        }

        sendMessage(chatId, header);

        for (Client c : filtered) {
            String text = String.format(
                    "🆔 ID: %s\n👤 ФИО: %s\n📞 Телефон: %s\n📍 Город: %s\n📌 Статус: %s",
                    c.id,
                    c.name,
                    c.phone,
                    c.city,
                    readableStatus(c.status)
            );
            sendInlineKeyboard(chatId, text, createClientActionsKeyboard(c));
        }
    }

    private String readableStatus(Client.Status status) {
        return switch (status) {
            case NEW -> "Предстоит связаться";
            case WAITING -> "Ждём ответа клиента";
            case COMPLETED -> "Задача выполнена";
            case DELETED -> "Удалено";
        };
    }

    /* ===================== КНОПКИ ДЕЙСТВИЙ ДЛЯ КЛИЕНТА ===================== */

    private InlineKeyboardMarkup createClientActionsKeyboard(Client client) {
        List<List<InlineKeyboardButton>> rows = new ArrayList<>();

        // Для новых заявок — начать работу
        if (client.status == Client.Status.NEW) {
            rows.add(List.of(
                    button("🚀 Связаться с клиентом", "contact:" + client.id)
            ));
        }

        // Для заявок в ожидании — завершить, перенести или удалить
        if (client.status == Client.Status.WAITING) {
            rows.add(List.of(
                    button("✅ Завершить", "done:" + client.id),
                    button("⏰ Перенести напоминание", "remind_menu:" + client.id)
            ));
            rows.add(List.of(
                    button("❌ Удалить (отказ)", "delete:" + client.id)
            ));
        }

        // Для удалённых — восстановить
        if (client.status == Client.Status.DELETED) {
            rows.add(List.of(
                    button("♻️ Восстановить", "restore:" + client.id)
            ));
        }

        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(rows);
        return markup;
    }

    /* ===================== ОБРАБОТКА CALLBACK-КНОПОК ===================== */

    private void handleCallback(long chatId, String data) {
        String[] parts = data.split(":");
        String action = parts[0];
        String clientId = parts[1];

        Client client = clients.stream()
                .filter(c -> c.id.equals(clientId))
                .findFirst()
                .orElse(null);

        if (client == null) {
            sendMessage(chatId, "Клиент не найден. Возможно, заявка была изменена.");
            return;
        }

        switch (action) {
            case "contact" -> {
                client.status = Client.Status.WAITING;
                sendMessage(chatId, "Статус изменён на «Отправлено, в ожидании ответа».\n" +
                        "Напоминание придёт через 1 час.");
                setReminder(chatId, client, 1, TimeUnit.HOURS);
            }
            case "done" -> {
                client.status = Client.Status.COMPLETED;
                sendMessage(chatId, "Задача по клиенту отмечена как выполненная ✅.");
            }
            case "delete" -> {
                client.status = Client.Status.DELETED;
                sendMessage(chatId, "Задача перемещена в удалённые.");
            }
            case "restore" -> {
                client.status = Client.Status.NEW;
                sendMessage(chatId, "Задача восстановлена и вернулась в «Предстоящие».");
            }
            case "remind_menu" -> {
                sendInlineKeyboard(
                        chatId,
                        "Выберите, когда напомнить снова:",
                        createDelayOptionsKeyboard(clientId)
                );
                return; // Не шлём "Обновлено."
            }
            case "delay" -> {
                int hours = Integer.parseInt(parts[2]);
                setReminder(chatId, client, hours, TimeUnit.HOURS);
                sendMessage(chatId, "⏳ Напоминание перенесено на " + hours + " ч.");
            }
        }
    }

    private InlineKeyboardMarkup createDelayOptionsKeyboard(String clientId) {
        InlineKeyboardMarkup markup = new InlineKeyboardMarkup();
        markup.setKeyboard(List.of(
                List.of(
                        button("1ч", "delay:" + clientId + ":1"),
                        button("2ч", "delay:" + clientId + ":2"),
                        button("8ч", "delay:" + clientId + ":8")
                ),
                List.of(
                        button("24ч", "delay:" + clientId + ":24"),
                        button("2 дня", "delay:" + clientId + ":48")
                )
        ));
        return markup;
    }

    /* ===================== ЛОГИКА НАПОМИНАНИЙ ===================== */

    private void setReminder(long chatId, Client client, long time, TimeUnit unit) {
        scheduler.schedule(() -> {
            // Напоминание только если статус ещё WAITING
            if (client.status == Client.Status.WAITING) {
                sendMessage(
                        chatId,
                        "🔔 НАПОМИНАНИЕ:\n" +
                                "Перезвоните клиенту: " + client.name + " (" + client.phone + "), город " + client.city + "."
                );
            }
        }, time, unit);
    }

    /* ===================== УТИЛИТЫ ДЛЯ ОТПРАВКИ СООБЩЕНИЙ И КНОПОК ===================== */

    private void sendMainMenu(long chatId, String text) {
        ReplyKeyboardMarkup markup = new ReplyKeyboardMarkup();
        markup.setResizeKeyboard(true);

        List<KeyboardRow> rows = new ArrayList<>();

        KeyboardRow row1 = new KeyboardRow();
        row1.add("📝 Создать заявку");

        KeyboardRow row2 = new KeyboardRow();
        row2.add("📂 Предстоящие");
        row2.add("⏳ В ожидании");

        KeyboardRow row3 = new KeyboardRow();
        row3.add("✅ Выполненные");
        row3.add("🗑 Удаленные");

        rows.add(row1);
        rows.add(row2);
        rows.add(row3);

        markup.setKeyboard(rows);

        SendMessage msg = new SendMessage(String.valueOf(chatId), text);
        msg.setReplyMarkup(markup);

        executeSafely(msg);
    }

    private void sendInlineKeyboard(long chatId, String text, InlineKeyboardMarkup keyboard) {
        SendMessage msg = new SendMessage(String.valueOf(chatId), text);
        msg.setReplyMarkup(keyboard);
        executeSafely(msg);
    }

    private void sendMessage(long chatId, String text) {
        SendMessage msg = new SendMessage(String.valueOf(chatId), text);
        executeSafely(msg);
    }

    private InlineKeyboardButton button(String text, String callbackData) {
        InlineKeyboardButton b = new InlineKeyboardButton(text);
        b.setCallbackData(callbackData);
        return b;
    }

    private void executeSafely(SendMessage msg) {
        try {
            execute(msg);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}