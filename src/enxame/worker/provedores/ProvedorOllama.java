package enxame.worker.provedores;

public class ProvedorOllama implements ProvedorLLM {

    static final String MODELO = "llama3.2:3b"; 

    public String nome() { return "ollama (" + MODELO + ")"; }

    public String responder(String pergunta) throws Exception {
        String json = "{\"model\":\"" + MODELO + "\",\"prompt\":" + Http.texto(pergunta) + ",\"stream\":false}";
        return Http.campo(Http.post("http://localhost:11434/api/generate", json, null), "response");
    }
}
