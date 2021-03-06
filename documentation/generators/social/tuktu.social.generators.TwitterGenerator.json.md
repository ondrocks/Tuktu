### tuktu.social.generators.TwitterGenerator
Given a list of keywords, users and a geo square, streams public tweets matching these filters.

  * **nodes** *(type: array)* `[Optional]`
  - Optionally specify on which nodes to run and how many instances you want on each node.

    * **[UNNAMED]** *(type: object)* `[Required]`

      * **type** *(type: string)* `[Required]`
      - The type of node handler, one of SingleNode, SomeNodes, AllNodes (leave empty for local execution)

      * **nodes** *(type: string)* `[Required]`
      - The nodes to use for this node handler type

      * **instances** *(type: int)* `[Optional, default = 1]`
      - The amount of instances per node of this handler type

  * **result** *(type: string)* `[Required]`

  * **stop_on_error** *(type: boolean)* `[Optional, default = true]`
  - If set to false, Tuktu will not kill the flow on data error.

  * **config** *(type: object)* `[Required]`

    * **credentials** *(type: object)* `[Required]`

      * **consumer_key** *(type: string)* `[Required]`

      * **consumer_secret** *(type: string)* `[Required]`

      * **access_token** *(type: string)* `[Required]`

      * **access_token_secret** *(type: string)* `[Required]`

    * **filters** *(type: object)* `[Required]`

      * **keywords** *(type: array)* `[Optional]`
      - Specifies the keywords to track.

        * **[UNNAMED]** *(type: string)* `[Required]`

      * **users** *(type: array)* `[Optional]`
      - Specifies the users, by ID, to receive public tweets from.

        * **[UNNAMED]** *(type: string)* `[Required]`

      * **geo** *(type: object)* `[Optional]`

        * **p1** *(type: object)* `[Optional]`

          * **long** *(type: string)* `[Optional]`
          - Define a geo square with these four values to filter tweets for. Can be omitted to search globally. If one is set, all have to be set.

          * **lat** *(type: string)* `[Optional]`
          - Define a geo square with these four values to filter tweets for. Can be omitted to search globally. If one is set, all have to be set.

        * **p2** *(type: object)* `[Optional]`

          * **long** *(type: string)* `[Optional]`
          - Define a geo square with these four values to filter tweets for. Can be omitted to search globally. If one is set, all have to be set.

          * **lat** *(type: string)* `[Optional]`
          - Define a geo square with these four values to filter tweets for. Can be omitted to search globally. If one is set, all have to be set.

