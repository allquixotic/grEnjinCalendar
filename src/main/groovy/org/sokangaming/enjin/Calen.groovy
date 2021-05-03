package org.sokangaming.enjin

import groovy.transform.Canonical
import org.apache.commons.collections4.list.SetUniqueList
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Canonical
class Calen implements Serializable {
    LocalDate date
    String messageId
    SetUniqueList<Evt> events

    private static final DateTimeFormatter fmt = DateTimeFormatter.ofPattern("EEEE MM/dd/YYYY")

    private String getDateDescription() {
        if(date == LocalDate.now()) {
            return "\uD83D\uDCCC **TODAY**, "
        }
        else if(date == LocalDate.now().plusDays(1L)) {
            return "➡️ TOMORROW, "
        }
        else if(date == LocalDate.now().minusDays(1L)) {
            return "⬅️YESTERDAY, "
        }
        else {
            return ""
        }
    }

    @Override
    String toString() {
        final def sb = new StringBuilder()
        sb.append("Here is ${getDateDescription(date)}${date.format(fmt)}'s calendar.\n")

        for(Evt e : events) {
            sb.append(e.toString()).append("\n")
        }

        sb.append("--------------------------")
        return sb.toString()
    }
}
