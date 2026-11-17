package enxame.comum;

/**
 * Mensagem trocada entre cliente, coordenador e workers.
 * O formato é definido na Etapa 2 (ver docs/protocolo.md).
 */
public class Mensagem {
    public String tipo;
    public String id;
    public String conteudo;

    public Mensagem(String tipo, String id, String conteudo){
        this.tipo = tipo;
        this.id = id;
        this.conteudo = conteudo;
    }

    // serialização
    public String paraLinha(){
        String textoEscapado = conteudo.replace("\n", "\\n");
        return tipo + "|" + id + "|" + textoEscapado;
    }

    // desserialização
    public static Mensagem deLinha(String linha){
        String[] partes = linha.split("\\|", 3);

        if(partes.length != 3){
            throw new IllegalArgumentException("linha inválida: " + linha);
        }

        String textoOriginal = partes[2].replace("\\n", "\n");

        return new Mensagem(partes[0], partes[1], textoOriginal);
    }
}
