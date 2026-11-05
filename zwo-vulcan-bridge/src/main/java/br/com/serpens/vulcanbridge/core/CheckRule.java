package br.com.serpens.vulcanbridge.core;

/**
 * Check do Vulcan aprovado para contar como evidência.
 *
 * @param index       posição na lista de regras (usada no buffer de evidências)
 * @param name        nome do check em minúsculas (ex.: "speed")
 * @param type        tipo em minúsculas (ex.: 'b') ou {@code 0} para todos os tipos do check
 * @param family      família do check (duas flags da mesma família não bastam)
 * @param familyIndex posição da família
 * @param minVl       VL mínimo que a flag precisa ter para contar
 * @param minFlags    quantas flags desse check, dentro da janela, a família precisa ter
 */
public record CheckRule(int index, String name, char type, String family, int familyIndex, int minVl, int minFlags) {

    /** "speed" ou "speed:b", como no config. */
    public String key() {
        return type == 0 ? name : name + ":" + type;
    }
}
