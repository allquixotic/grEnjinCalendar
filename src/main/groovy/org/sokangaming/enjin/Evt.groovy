package org.sokangaming.enjin

import groovy.transform.Canonical
import java.time.LocalTime;

@Canonical
class Evt implements Serializable {
    String name
    boolean recurs
    LocalTime time

    @Override
    String toString() {
        return """"$name" ${recurs ? "(recurring) " : ""}at ${time.format(EventScraper.hmma)} ET"""
    }
}
