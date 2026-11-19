package enxame.coordenador;

import java.io.*;
import java.net.*; 
import enxame.comum.Mensagem;

// liga pro worker e passa para o coordenador
public record StubWorker(String host, int porta, String nome) { 

    public Mensagem perguntar(Mensagem pergunta) throws IOException {
        try (Socket s = new Socket()) { // telefone vazio, ainda nao ligou

            s.connect(new InetSocketAddress(host, porta), 1000); // liga pro worker. se nao atender em 1 s, desiste
            s.setSoTimeout(30000); // se o worker nao responder em 30 s, desiste (worker travado ou lento). LLM real demora mais que o falso

            // pega a saida e a entrada (so funciona depois que ja ligou)
            PrintWriter saida = new PrintWriter(s.getOutputStream(), true);
            BufferedReader entrada = new BufferedReader(new InputStreamReader(s.getInputStream()));

            saida.println(pergunta.paraLinha());          // FALA: manda a pergunta pro worker
            return Mensagem.deLinha(entrada.readLine());  // ESCUTA: espera a resposta e devolve
        }
    }
}
