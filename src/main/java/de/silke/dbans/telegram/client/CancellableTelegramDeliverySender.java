package de.silke.dbans.telegram.client;

interface CancellableTelegramDeliverySender extends TelegramDeliverySender {

    void cancelAllPending();
}