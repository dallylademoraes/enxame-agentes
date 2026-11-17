package enxame.worker.provedores;

// LLM na nuvem: Groq. A chave vem da variavel de ambiente GROQ_API_KEY (nunca fica no codigo).
public class ProvedorGroq implements ProvedorLLM {

    static final String MODELO = "openai/gpt-oss-20b"; // modelos disponiveis: https://console.groq.com/docs/models

    public String nome() { return "groq (" + MODELO + ")"; }

    public String responder(String pergunta) throws Exception {
        String chave = System.getenv("GROQ_API_KEY");
        if (chave == null) throw new Exception("variavel GROQ_API_KEY nao definida");
        String json = "{\"model\":\"" + MODELO + "\",\"messages\":[{\"role\":\"user\",\"content\":" + Http.texto(pergunta) + "}]}";
        return Http.campo(Http.post("https://api.groq.com/openai/v1/chat/completions", json, chave), "content");
    }
}
