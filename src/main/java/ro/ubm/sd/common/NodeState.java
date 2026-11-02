package ro.ubm.sd.common;

/** Starea unui nod. În Etapa 2 nodurile descoperite sunt ACTIV; tranzițiile vin cu heartbeat-ul din Etapa 3. */
public enum NodeState {
    ACTIV, SUSPECT, CAZUT
}
