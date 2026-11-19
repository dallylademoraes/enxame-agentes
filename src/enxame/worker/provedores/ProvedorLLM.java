package enxame.worker.provedores;

//Interface dos provedores
public interface ProvedorLLM {

    String nome();

    String responder(String pergunta) throws Exception;
}
