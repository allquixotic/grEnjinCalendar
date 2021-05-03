package org.sokangaming.discord

import com.google.api.client.util.Strings;
import discord4j.common.util.Snowflake;
import discord4j.core.DiscordClient;
import discord4j.core.event.domain.lifecycle.ReadyEvent
import discord4j.discordjson.json.MessageEditRequest;
import discord4j.gateway.intent.Intent;
import discord4j.gateway.intent.IntentSet
import groovy.util.logging.Log
import org.sokangaming.enjin.Calen
import org.sokangaming.enjin.CalendarUpdateService
import org.sokangaming.enjin.Config
import org.sokangaming.enjin.EventScraper
import org.apache.commons.lang3.exception.ExceptionUtils
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

import java.util.concurrent.TimeUnit;

@Log @Singleton(lazy=true)
class DiscordBotService {
    EventScraper getScraper() {
        CalendarUpdateService?.instance?.scraper
    }
    void run(Config conf) throws IOException {
        if(!conf) {
            throw new RuntimeException("Config is empty!")
        }

        final def client = DiscordClient.create(conf.getDiscordSecret())
        final def gateway = client.gateway().setEnabledIntents(IntentSet.of(Intent.GUILDS) as IntentSet).login().block()
        final Runnable post = () -> {
            for (def guild : conf.calendarUrls.keySet()) {
                log.info("Processing update for ${guild}...")
                if(!conf.discordChannels[guild]) continue
                def flake = Snowflake.of(conf.discordChannels[guild])
                final def chan = gateway.getChannelById(flake).block()
                log.info("Got the channel we're working on.")
                final def calens = new Calen[]{
                        scraper.getCalendar(LocalDate.now().minusDays(1)),
                        scraper.getCalendar(LocalDate.now()),
                        scraper.getCalendar(LocalDate.now().plusDays(1))
                }

                var eye = 0
                for (final def calen : calens) {
                    if (calen != null) {
                        if (!Strings.isNullOrEmpty(calen.getMessageId())) {
                            try {
                                final def msgFlake = Snowflake.of(calen.getMessageId())
                                final def msg = gateway.getMessageById(flake, msgFlake).block()
                                final def theMsg = msg.getContent()
                                if (!calen.toString().trim().equalsIgnoreCase(theMsg)) {
                                    log.info("Updating message for " + calen.getDate().format(DateTimeFormatter.ISO_LOCAL_DATE));
                                    var rm = chan.getRestChannel().getRestMessage(msgFlake)
                                    rm.edit(MessageEditRequest.builder().content(calen.toString()).build()).block();
                                }
                            }
                            catch (Exception e) {
                                log.severe(ExceptionUtils.getStackTrace(e));
                                final def newMsg = chan.getRestChannel().createMessage(calen.toString()).block();
                                calen.setMessageId(newMsg.id() as String);
                                scraper.updateCalen(calen.getDate(), calen);
                            }
                        } else {
                            final def newMsg = chan.getRestChannel().createMessage(calen.toString()).block();
                            calen.setMessageId(newMsg.id() as String);
                            scraper.updateCalen(calen.getDate(), calen);
                        }
                    } else {
                        log.info("Calen null: " + eye);
                    }
                    eye++;
                }

                try {
                    final def obs = scraper.getCalendar(LocalDate.now().minusDays(2));
                    if (obs != null) {
                        final def obsolete = obs.getMessageId();
                        if (obsolete != null) {
                            final def rm = chan.getRestChannel().getRestMessage(Snowflake.of(obsolete));
                            rm.delete("Obsolete message deleted by Calendar bot").block();
                            obs.setMessageId(null);
                            scraper.updateCalen(obs.getDate(), obs);
                        } else {
                            log.info("No obsolete message ID present on Calen.");
                        }
                    } else {
                        log.info("DB didn't have a calendar record for obsolete day.");
                    }
                }
                catch (Exception ee) {
                    log.severe(ExceptionUtils.getStackTrace(ee));
                }
            }
        }

        gateway.onDisconnect().repeat(() -> {
            while (true) {
                try {
                    sleep(15 * 60 * 1000);
                    client.login().block();
                    break;
                } catch (Exception e) {
                    log.severe(ExceptionUtils.getStackTrace(e));
                    sleep(10 * 60 * 1000);
                }
            }
            return true;
        });

        gateway.on(ReadyEvent.class).subscribe((rdy) -> {
            log.info("Discord Ready event received.");
            client.getCoreResources().getReactorResources().getTimerTaskScheduler().schedulePeriodically(() -> {
                CalendarUpdateService.instance.pool.submit(post);
            }, 0, 15, TimeUnit.MINUTES);
        });
    }
}
