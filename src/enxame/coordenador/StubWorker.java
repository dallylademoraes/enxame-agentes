package enxame.coordenador;

import java.io.*;
import java.net.*; 
import enxame.comum.Mensagem;

// "Contato" de um worker: liga, pergunta, devolve a resposta ao Coordenador.
public record StubWorker(String host, int porta, String nome) { // nome = provedor (ollama, groq, falso), so pra mostrar

    public Mensagem perguntar(Mensagem pergunta) throws IOException {
        try (Socket s = new Socket()) { // telefone vazio, ainda nao ligou

            s.connect(new InetSocketAddress(host, porta), 1000); // liga pro worker. se nao atender em 1 s, desiste
            s.setSoTimeout(30000); // se o worker nao responder em 30 s, desiste (worker travado ou lento). LLM real demora mais que o falso

            // pega a saida e a entrada (so funciona depois que ja ligou)
            PrintWriter saida = new PrintWriter(s.getOutputStream(), true);
            BufferedReader entrada = new BufferedReader(new InputStreamReader(s.getInputStream()));

            saida.println(pergunta.paraLinha());          // fala
            return Mensagem.deLinha(entrada.readLine());  // escuta e devolve
        }
    }
}
