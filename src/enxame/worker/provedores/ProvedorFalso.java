package enxame.worker.provedores;

import java.util.concurrent.ThreadLocalRandom;

// Finge ser um LLM: demora 0,5 a 2 s, falha em 20% das vezes e as vezes "erra" a resposta
public class ProvedorFalso implements ProvedorLLM {

    public String nome() { return "falso"; }

    public String responder(String pergunta) throws Exception {
        Thread.sleep(ThreadLocalRandom.current().nextInt(500, 2001));// finge pensamento
        if (ThreadLocalRandom.current().nextInt(5) == 0) throw new Exception("falha simulada");
        // responde Paris ou Lyon no else
        return ThreadLocalRandom.current().nextInt(10) < 7 ? "Paris" : "Lyon";
    }
}
