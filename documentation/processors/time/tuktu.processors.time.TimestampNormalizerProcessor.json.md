### tuktu.processors.time.TimestampNormalizerProcessor
Floors a given timestamp, based on the time fields; e.g. floored to the nearest 10 minutes.

  * **id** *(type: string)* `[Required]`

  * **result** *(type: string)* `[Required]`

  * **config** *(type: object)* `[Required]`

    * **datetime_format** *(type: string)* `[Optional]`
    - The datetime format to parse the given field with. Can be left empty if field is already a datetime object or a long timestamp.

    * **datetime_field** *(type: string)* `[Required]`
    - The field which contains a formatted datetime, a datetime object, or a long timestamp.

    * **datetime_locale** *(type: string)* `[Optional, default = "en"]`
    - The locale of the datetime format to parse the field with.

    * **time** *(type: object)* `[Required]`

      * **years** *(type: int)* `[Optional]`
      - Floor the timestamp to the nearest time frame. Only takes largest unit into account, so if 10 years and 10 hours is present, only 10 years is used.

      * **months** *(type: int)* `[Optional]`
      - Floor the timestamp to the nearest time frame. Only takes largest unit into account, so if 10 years and 10 hours is present, only 10 years is used.

      * **days** *(type: int)* `[Optional]`
      - Floor the timestamp to the nearest time frame. Only takes largest unit into account, so if 10 years and 10 hours is present, only 10 years is used.

      * **hours** *(type: int)* `[Optional]`
      - Floor the timestamp to the nearest time frame. Only takes largest unit into account, so if 10 years and 10 hours is present, only 10 years is used.

      * **minutes** *(type: int)* `[Optional]`
      - Floor the timestamp to the nearest time frame. Only takes largest unit into account, so if 10 years and 10 hours is present, only 10 years is used.

      * **seconds** *(type: int)* `[Optional]`
      - Floor the timestamp to the nearest time frame. Only takes largest unit into account, so if 10 years and 10 hours is present, only 10 years is used.

      * **millis** *(type: int)* `[Optional]`
      - Floor the timestamp to the nearest time frame. Only takes largest unit into account, so if 10 years and 10 hours is present, only 10 years is used.

