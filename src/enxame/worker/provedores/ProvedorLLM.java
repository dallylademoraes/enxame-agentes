package enxame.worker.provedores;

/**
 * Abstração de um modelo de linguagem. O worker não sabe qual LLM está por trás.
 */
public interface ProvedorLLM {

    String nome();

    String responder(String pergunta) throws Exception;
}
