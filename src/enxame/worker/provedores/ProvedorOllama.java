package enxame.worker.provedores;

// LLM local: o Ollama roda no proprio PC (http://localhost:11434). Sem internet e sem chave.
public class ProvedorOllama implements ProvedorLLM {

    static final String MODELO = "llama3.2:3b"; // baixar antes com "ollama pull llama3.2:3b" (o 1b era pequeno demais e errava muito)

    public String nome() { return "ollama (" + MODELO + ")"; }

    public String responder(String pergunta) throws Exception {
        String json = "{\"model\":\"" + MODELO + "\",\"prompt\":" + Http.texto(pergunta) + ",\"stream\":false}";
        return Http.campo(Http.post("http://localhost:11434/api/generate", json, null), "response");
    }
}
