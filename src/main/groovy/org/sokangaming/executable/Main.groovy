package org.sokangaming.executable

import groovy.util.logging.Log
import org.sokangaming.discord.DiscordBotService
import org.sokangaming.enjin.Config
@Log
class Main {
    static void main(String[] args) throws IOException {
        final def conf = Config.global = Config.readConfig(args?.find() as String)
        DiscordBotService.instance.run(conf)
    }
}
